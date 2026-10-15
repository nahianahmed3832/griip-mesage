import crypto from "node:crypto";
import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { type AppContext, bearer, me, parse, requireAuth, zKey, zName, zSig } from "../context.js";
import { canonical, isValidPublicKey, keyFingerprint, verifyEd25519 } from "../crypto.js";
import { ApiError, badRequest, conflict, forbidden, humanCode, newId, normalizeCode, notFound, now, sha256Hex, unauthorized } from "../util.js";

const USERNAME = /^[a-z0-9](?:[a-z0-9._]{1,22})[a-z0-9]$/;
const CHALLENGE_TTL_MS = 2 * 60 * 1000;
const PAIRING_TTL_MS = 10 * 60 * 1000;

const zUsername = z
  .string()
  .trim()
  .toLowerCase()
  .refine((u) => USERNAME.test(u) && !u.includes(".."), "3–24 chars: a–z, 0–9, '.', '_'; must start and end with a letter or digit");

const zDevice = z.object({
  name: zName,
  signingKey: zKey,
  encryptionKey: zKey,
  certificate: zSig,
});

/** Consume a single-use challenge nonce. Returns false if unknown/expired/already used. */
function consumeChallenge(ctx: AppContext, nonce: string): boolean {
  const r = ctx.db.prepare("DELETE FROM auth_challenges WHERE nonce = ? AND expires_at > ? RETURNING nonce").get(nonce, now());
  return !!r;
}

function issueSession(ctx: AppContext, userId: string, deviceId: string) {
  const token = crypto.randomBytes(32).toString("base64url");
  const t = now();
  const expiresAt = t + ctx.config.sessionTtlSeconds * 1000;
  ctx.db
    .prepare("INSERT INTO sessions (token_hash, user_id, device_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
    .run(sha256Hex(token), userId, deviceId, t, expiresAt);
  ctx.db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").run(t, deviceId);
  return { token, expiresAt };
}

function verifyDevice(identityKey: string, d: z.infer<typeof zDevice>): void {
  if (!isValidPublicKey(identityKey) || !isValidPublicKey(d.signingKey) || !isValidPublicKey(d.encryptionKey)) {
    throw badRequest("Malformed public key");
  }
  if (!verifyEd25519(identityKey, canonical.deviceCert(identityKey, d.signingKey, d.encryptionKey), d.certificate)) {
    throw new ApiError(401, "invalid_certificate", "Device certificate is not signed by the account identity key");
  }
}

export function userSummary(u: {
  id: string;
  username: string;
  display_name: string;
  avatar_color: number;
  status_text: string | null;
  identity_key?: string;
}) {
  return {
    id: u.id,
    username: u.username,
    displayName: u.display_name,
    avatarColor: u.avatar_color,
    statusText: u.status_text,
  };
}

export default async function authRoutes(app: FastifyInstance, ctx: AppContext) {
  const strict = { config: { rateLimit: { max: 30, timeWindow: "1 minute" } } };

  app.post("/v1/auth/challenge", strict, async () => {
    const nonce = crypto.randomBytes(24).toString("base64url");
    const t = now();
    ctx.db.prepare("INSERT INTO auth_challenges (nonce, created_at, expires_at) VALUES (?, ?, ?)").run(nonce, t, t + CHALLENGE_TTL_MS);
    return { nonce, expiresAt: t + CHALLENGE_TTL_MS };
  });

  app.get("/v1/auth/username-available", strict, async (req) => {
    const q = parse(z.object({ username: z.string() }), req.query);
    const ok = zUsername.safeParse(q.username);
    if (!ok.success) return { available: false, reason: ok.error.issues[0]?.message };
    const taken = ctx.db.prepare("SELECT 1 FROM users WHERE username = ?").get(ok.data);
    return { available: !taken, username: ok.data };
  });

  // Register a new account: proves possession of the device key (signs a fresh nonce) and
  // binds the device to the account identity key via a certificate signed by that key.
  app.post("/v1/auth/register", strict, async (req) => {
    const body = parse(
      z.object({
        username: zUsername,
        displayName: zName,
        identityKey: zKey,
        avatarColor: z.number().int().min(0).max(15).default(0),
        device: zDevice,
        nonce: z.string().min(16).max(64),
        signature: zSig,
      }),
      req.body,
    );
    verifyDevice(body.identityKey, body.device);
    if (!verifyEd25519(body.device.signingKey, canonical.authProof("register", body.nonce, body.device.signingKey), body.signature)) {
      throw unauthorized("Invalid proof signature");
    }
    if (!consumeChallenge(ctx, body.nonce)) throw unauthorized("Challenge expired or already used");

    const t = now();
    const userId = newId("u");
    const deviceId = newId("d");
    try {
      ctx.db.transaction(() => {
        ctx.db
          .prepare(
            "INSERT INTO users (id, username, display_name, identity_key, avatar_color, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
          )
          .run(userId, body.username, body.displayName, body.identityKey, body.avatarColor, t, t);
        ctx.db
          .prepare(
            "INSERT INTO devices (id, user_id, name, signing_key, encryption_key, certificate, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
          )
          .run(deviceId, userId, body.device.name, body.device.signingKey, body.device.encryptionKey, body.device.certificate, t);
      })();
    } catch (e) {
      if (String(e).includes("UNIQUE")) throw conflict("That username is already taken");
      throw e;
    }
    ctx.audit({ actorId: userId, action: "user.register", target: userId });
    const session = issueSession(ctx, userId, deviceId);
    return { userId, deviceId, sessionToken: session.token, expiresAt: session.expiresAt };
  });

  app.post("/v1/auth/login", strict, async (req) => {
    const body = parse(z.object({ deviceId: z.string().max(64), nonce: z.string().min(16).max(64), signature: zSig }), req.body);
    const dev = ctx.db
      .prepare("SELECT d.*, u.disabled_at FROM devices d JOIN users u ON u.id = d.user_id WHERE d.id = ?")
      .get(body.deviceId) as { id: string; user_id: string; signing_key: string; revoked_at: number | null; disabled_at: number | null } | undefined;
    // Same error for unknown device and bad signature: don't reveal which device ids exist.
    if (!dev || !verifyEd25519(dev.signing_key, canonical.authProof("login", body.nonce, dev.signing_key), body.signature)) {
      throw unauthorized("Invalid device proof");
    }
    if (!consumeChallenge(ctx, body.nonce)) throw unauthorized("Challenge expired or already used");
    if (dev.revoked_at) throw new ApiError(401, "device_revoked", "This device has been removed from the account");
    if (dev.disabled_at) throw new ApiError(401, "account_disabled", "This account is disabled");
    const session = issueSession(ctx, dev.user_id, dev.id);
    return { userId: dev.user_id, deviceId: dev.id, sessionToken: session.token, expiresAt: session.expiresAt };
  });

  // Add a device to an existing account (pairing or recovery). The certificate must be
  // signed by the account's identity key — only someone holding that key can authorise it.
  app.post("/v1/auth/add-device", strict, async (req) => {
    const body = parse(
      z.object({ username: zUsername, device: zDevice, nonce: z.string().min(16).max(64), signature: zSig }),
      req.body,
    );
    const user = ctx.db.prepare("SELECT * FROM users WHERE username = ?").get(body.username) as
      | { id: string; identity_key: string; disabled_at: number | null }
      | undefined;
    if (!user) throw unauthorized("Invalid device proof");
    verifyDevice(user.identity_key, body.device);
    if (!verifyEd25519(body.device.signingKey, canonical.authProof("add-device", body.nonce, body.device.signingKey), body.signature)) {
      throw unauthorized("Invalid proof signature");
    }
    if (!consumeChallenge(ctx, body.nonce)) throw unauthorized("Challenge expired or already used");
    if (user.disabled_at) throw new ApiError(401, "account_disabled", "This account is disabled");
    const deviceId = newId("d");
    const t = now();
    try {
      ctx.db
        .prepare(
          "INSERT INTO devices (id, user_id, name, signing_key, encryption_key, certificate, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        )
        .run(deviceId, user.id, body.device.name, body.device.signingKey, body.device.encryptionKey, body.device.certificate, t);
    } catch (e) {
      if (String(e).includes("UNIQUE")) throw conflict("This device key is already registered");
      throw e;
    }
    ctx.audit({ actorId: user.id, action: "device.add", target: deviceId });
    // Other members must re-fetch device lists so new messages are also encrypted to this device.
    notifyKeyChange(ctx, user.id);
    const session = issueSession(ctx, user.id, deviceId);
    return { userId: user.id, deviceId, sessionToken: session.token, expiresAt: session.expiresAt };
  });

  app.post("/v1/auth/logout", { preHandler: requireAuth(ctx) }, async (req) => {
    const token = bearer(req)!;
    ctx.db.prepare("DELETE FROM sessions WHERE token_hash = ?").run(sha256Hex(token));
    return { ok: true };
  });

  // ───────────── Device pairing (link a new phone to an existing account) ─────────────
  app.post("/v1/pairing", strict, async (req) => {
    const body = parse(
      z.object({ deviceName: zName, signingKey: zKey, encryptionKey: zKey, transferKey: zKey }),
      req.body,
    );
    if (![body.signingKey, body.encryptionKey, body.transferKey].every(isValidPublicKey)) throw badRequest("Malformed public key");
    const id = crypto.randomBytes(24).toString("base64url");
    const code = humanCode(10);
    const t = now();
    ctx.db
      .prepare(
        `INSERT INTO pairing_requests (id, code_hash, device_name, signing_key, encryption_key, transfer_key, created_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(id, sha256Hex(code), body.deviceName, body.signingKey, body.encryptionKey, body.transferKey, t, t + PAIRING_TTL_MS);
    return { pairingId: id, code, expiresAt: t + PAIRING_TTL_MS };
  });

  app.get("/v1/pairing/:pairingId", async (req) => {
    const { pairingId } = parse(z.object({ pairingId: z.string().min(20).max(64) }), req.params);
    const r = ctx.db.prepare("SELECT * FROM pairing_requests WHERE id = ?").get(pairingId) as
      | {
          approved_user_id: string | null;
          certificate: string | null;
          sealed_identity: string | null;
          sealed_enc: string | null;
          expires_at: number;
        }
      | undefined;
    if (!r || r.expires_at <= now()) return { status: "expired" };
    if (!r.approved_user_id) return { status: "pending" };
    const u = ctx.db.prepare("SELECT username, identity_key FROM users WHERE id = ?").get(r.approved_user_id) as {
      username: string;
      identity_key: string;
    };
    return {
      status: "approved",
      username: u.username,
      identityKey: u.identity_key,
      certificate: r.certificate,
      sealedIdentity: r.sealed_identity,
      sealedEnc: r.sealed_enc,
    };
  });

  app.get("/v1/pairing/code/:code", { preHandler: requireAuth(ctx) }, async (req) => {
    const { code } = parse(z.object({ code: z.string().max(32) }), req.params);
    const r = ctx.db
      .prepare("SELECT * FROM pairing_requests WHERE code_hash = ? AND expires_at > ? AND approved_user_id IS NULL")
      .get(sha256Hex(normalizeCode(code)), now()) as
      | { device_name: string; signing_key: string; encryption_key: string; transfer_key: string; created_at: number }
      | undefined;
    if (!r) throw notFound("Pairing code not found or expired");
    return {
      deviceName: r.device_name,
      signingKey: r.signing_key,
      encryptionKey: r.encryption_key,
      transferKey: r.transfer_key,
      createdAt: r.created_at,
    };
  });

  app.post("/v1/pairing/code/:code/approve", { preHandler: requireAuth(ctx) }, async (req) => {
    const { userId } = me(req);
    const { code } = parse(z.object({ code: z.string().max(32) }), req.params);
    const body = parse(
      z.object({ certificate: zSig, sealedIdentity: z.string().min(10).max(400), sealedEnc: zKey }),
      req.body,
    );
    const r = ctx.db
      .prepare("SELECT * FROM pairing_requests WHERE code_hash = ? AND expires_at > ? AND approved_user_id IS NULL")
      .get(sha256Hex(normalizeCode(code)), now()) as { id: string; signing_key: string; encryption_key: string } | undefined;
    if (!r) throw notFound("Pairing code not found or expired");
    const u = ctx.db.prepare("SELECT identity_key FROM users WHERE id = ?").get(userId) as { identity_key: string };
    if (!verifyEd25519(u.identity_key, canonical.deviceCert(u.identity_key, r.signing_key, r.encryption_key), body.certificate)) {
      throw forbidden("Certificate does not match your identity key");
    }
    ctx.db
      .prepare("UPDATE pairing_requests SET approved_user_id = ?, certificate = ?, sealed_identity = ?, sealed_enc = ? WHERE id = ?")
      .run(userId, body.certificate, body.sealedIdentity, body.sealedEnc, r.id);
    ctx.audit({ actorId: userId, action: "device.pairing_approved" });
    return { ok: true };
  });

  // ───────────── Own devices ─────────────
  app.get("/v1/devices", { preHandler: requireAuth(ctx) }, async (req) => {
    const { userId, deviceId } = me(req);
    const rows = ctx.db
      .prepare("SELECT id, name, signing_key, created_at, last_seen_at, revoked_at FROM devices WHERE user_id = ? ORDER BY created_at")
      .all(userId) as { id: string; name: string; signing_key: string; created_at: number; last_seen_at: number | null; revoked_at: number | null }[];
    return {
      devices: rows.map((d) => ({
        id: d.id,
        name: d.name,
        signingKey: d.signing_key,
        createdAt: d.created_at,
        lastSeenAt: d.last_seen_at,
        revokedAt: d.revoked_at,
        current: d.id === deviceId,
        online: ctx.hub.isUserOnline(userId),
      })),
    };
  });

  app.delete("/v1/devices/:id", { preHandler: requireAuth(ctx) }, async (req) => {
    const { userId, deviceId } = me(req);
    const { id } = parse(z.object({ id: z.string().max(64) }), req.params);
    const d = ctx.db.prepare("SELECT * FROM devices WHERE id = ? AND user_id = ?").get(id, userId) as
      | { id: string; revoked_at: number | null }
      | undefined;
    if (!d) throw notFound("Device not found");
    if (d.revoked_at) return { ok: true };
    const t = now();
    ctx.db.transaction(() => {
      ctx.db.prepare("UPDATE devices SET revoked_at = ? WHERE id = ?").run(t, id);
      ctx.db.prepare("DELETE FROM sessions WHERE device_id = ?").run(id);
      ctx.db.prepare("DELETE FROM delivery_queue WHERE recipient_device_id = ?").run(id);
    })();
    ctx.audit({ actorId: userId, action: "device.revoke", target: id, detail: id === deviceId ? "self" : null });
    ctx.hub.disconnectDevice(id, "device revoked");
    notifyKeyChange(ctx, userId);
    return { ok: true };
  });

  app.get("/v1/fingerprint/:userId", { preHandler: requireAuth(ctx) }, async (req) => {
    const { userId } = parse(z.object({ userId: z.string().max(64) }), req.params);
    const u = ctx.db.prepare("SELECT identity_key FROM users WHERE id = ?").get(userId) as { identity_key: string } | undefined;
    if (!u) throw notFound();
    return { fingerprint: keyFingerprint(u.identity_key) };
  });
}

/** Tell everyone who shares a conversation with this user to refresh its device list. */
export function notifyKeyChange(ctx: AppContext, userId: string): void {
  const peers = ctx.db
    .prepare(
      `SELECT DISTINCT m2.user_id FROM conversation_members m1
       JOIN conversation_members m2 ON m2.conversation_id = m1.conversation_id
       WHERE m1.user_id = ?`,
    )
    .all(userId) as { user_id: string }[];
  ctx.hub.sendToUsers(
    peers.map((p) => p.user_id),
    { t: "devices.changed", userId },
  );
}
