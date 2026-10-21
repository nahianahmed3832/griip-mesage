import crypto from "node:crypto";
import type { z } from "zod";
import { DAY_MS } from "../abuse.js";
import type { AppContext } from "../context.js";
import { canonical, isValidPublicKey, verifyEd25519 } from "../crypto.js";
import { joinOrgByInvite, type OrgInviteRow } from "../invites.js";
import { notifyKeyChange, revokeDeviceRows, zUsername } from "../routes/auth.js";
import { evictUserDevicesFromCalls } from "../routes/calls.js";
import { ApiError, badRequest, newId, now } from "../util.js";
import type { enrollDeviceBody, resetIdentityBody } from "./schemas.js";
import { consumeChallenge, type AccountService } from "./service.js";
import type { AccountRow, ActiveSession } from "./store.js";

/** What setting up a device did: made the account's encryption identity, added a device to it, or found it already there. */
export type EnrollResult = "identity" | "device" | "existing";

export interface EnrollResponse {
  userId: string;
  deviceId: string;
  username: string;
  result: EnrollResult;
}

const AVATAR_COLORS = 16;
const USERNAME_ATTEMPTS = 8;

type DeviceKeys = z.infer<typeof enrollDeviceBody>["device"];

function requireKeys(d: DeviceKeys, identityKey: string): void {
  if (!isValidPublicKey(identityKey) || !isValidPublicKey(d.signingKey) || !isValidPublicKey(d.encryptionKey)) throw badRequest("Malformed public key");
  if (!verifyEd25519(identityKey, canonical.deviceCert(identityKey, d.signingKey, d.encryptionKey), d.certificate)) {
    throw new ApiError(401, "invalid_certificate", "This device's certificate isn't signed by the account's identity key");
  }
}

function requireProof(ctx: AppContext, purpose: "register" | "add-device", d: DeviceKeys, nonce: string, signature: string): void {
  if (!verifyEd25519(d.signingKey, canonical.authProof(purpose, nonce, d.signingKey), signature)) throw new ApiError(401, "invalid_proof", "Invalid proof signature");
  if (!consumeChallenge(ctx, nonce)) throw new ApiError(401, "invalid_proof", "The challenge expired or was already used. Try again.");
}

/** A unique-constraint failure as the person should hear it: their device key was used before, or the username is taken. */
function uniqueConflict(e: unknown): unknown {
  const text = String(e);
  if (text.includes("devices.signing_key")) return new ApiError(409, "device_key_retired", "These device keys can't be used again. The app will make new ones.");
  if (text.includes("users.username")) return new ApiError(409, "username_taken", "That username is already taken. Try again.");
  return e;
}

/** "ada.lovelace" → "adalovelace4821": a free username from the person's name, which they can change later. */
export function usernameCandidate(name: string): string {
  const base = name
    .normalize("NFKD")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "")
    .slice(0, 16);
  const stem = base.length >= 2 ? base : "meldiq";
  return `${stem}${crypto.randomInt(1000, 10000)}`;
}

function freeUsername(ctx: AppContext, wanted: string | undefined, name: string): string {
  if (wanted) {
    const parsed = zUsername.safeParse(wanted);
    if (!parsed.success) throw new ApiError(400, "bad_username", parsed.error.issues[0]?.message ?? "That username isn't allowed");
    if (ctx.db.prepare("SELECT 1 FROM users WHERE username = ?").get(parsed.data)) throw new ApiError(409, "username_taken", "That username is already taken");
    return parsed.data;
  }
  for (let i = 0; i < USERNAME_ATTEMPTS; i++) {
    const c = usernameCandidate(name);
    if (zUsername.safeParse(c).success && !ctx.db.prepare("SELECT 1 FROM users WHERE username = ?").get(c)) return c;
  }
  return `meldiq${crypto.randomBytes(5).toString("hex")}`;
}

function insertDevice(ctx: AppContext, userId: string, d: DeviceKeys, t: number): string {
  const deviceId = newId("d");
  ctx.db
    .prepare("INSERT INTO devices (id, user_id, name, signing_key, encryption_key, certificate, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
    .run(deviceId, userId, d.name, d.signingKey, d.encryptionKey, d.certificate, t);
  return deviceId;
}

/**
 * Device setup after sign-in (docs/ORBIT_2_ARCHITECTURE §3). The account's first device creates its encryption identity;
 * later devices must carry a certificate from that identity (made with the recovery key, or by another device through
 * pairing). Either way the calling session is bound to the device, which then may use the messaging routes.
 */
export class IdentityService {
  constructor(
    private readonly ctx: AppContext,
    private readonly accounts: AccountService,
  ) {}

  async enroll(s: ActiveSession, body: z.infer<typeof enrollDeviceBody>): Promise<EnrollResponse> {
    let a = (await this.accounts.store.accountById(s.account_id))!;
    // A link interrupted half-way (MongoDB accounts: the account was claimed, the identity's rows never written) is
    // undone, so the person can set up encryption again instead of being stuck.
    if (a.user_id && !this.ctx.db.prepare("SELECT 1 FROM users WHERE id = ?").get(a.user_id)) {
      await this.accounts.store.releaseIdentity(a.id, a.user_id, now());
      a = (await this.accounts.store.accountById(s.account_id))!;
    }
    return a.user_id ? this.addDevice(s, a, a.user_id, body) : this.createIdentity(s, a, body);
  }

  private async createIdentity(s: ActiveSession, a: AccountRow, body: z.infer<typeof enrollDeviceBody>): Promise<EnrollResponse> {
    if (!body.identityKey) throw new ApiError(400, "identity_required", "This account has no encryption keys yet: send the new identity key");
    requireKeys(body.device, body.identityKey);
    const username = freeUsername(this.ctx, body.username, a.name);
    requireProof(this.ctx, "register", body.device, body.nonce, body.signature);
    const t = now();
    const abuse = this.ctx.config.abuse;
    const limitedUntil = this.ctx.config.registrationMode === "open" && abuse.newAccountDays > 0 ? t + abuse.newAccountDays * DAY_MS : null;
    const userId = newId("u");
    const identityKey = body.identityKey;
    let deviceId: string | null;
    try {
      // The identity's rows and the account's link to them: both or neither (AccountStore.linkIdentity).
      deviceId = await this.accounts.store.linkIdentity(a.id, userId, s.id, t, () => {
        this.ctx.db
          .prepare("INSERT INTO users (id, username, display_name, identity_key, avatar_color, created_at, updated_at, limited_until) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
          .run(userId, username, a.name, identityKey, crypto.randomInt(0, AVATAR_COLORS), t, t, limitedUntil);
        const id = insertDevice(this.ctx, userId, body.device, t);
        this.joinAdmittingOrg(a, userId, t);
        return id;
      });
    } catch (e) {
      throw uniqueConflict(e);
    }
    // Another request for this account got here first (a retry or a double tap): use what it made.
    if (deviceId === null) throw new ApiError(409, "identity_exists", "This account already has encryption keys. Try again.");
    // "account" in the detail: the sign-up was already counted when the account was made (abuse.openRegistrationsToday).
    this.ctx.audit({ actorId: userId, action: "user.register", target: userId, detail: "account" });
    return { userId, deviceId, username, result: "identity" };
  }

  /**
   * An account admitted by an organisation invite (invite-only servers) joins that organisation with its new identity.
   * The invite's use was taken at sign-up; if it can't be used any more (revoked, its scope archived), the person simply
   * isn't joined and can join later with another invite.
   */
  private joinAdmittingOrg(a: AccountRow, userId: string, t: number): void {
    const inviteId = a.admitted_via?.startsWith("org-invite:") ? a.admitted_via.slice("org-invite:".length) : null;
    if (!inviteId) return;
    const inv = this.ctx.db.prepare("SELECT * FROM invitations WHERE id = ?").get(inviteId) as OrgInviteRow | undefined;
    if (!inv || inv.revoked_at) return;
    if (!this.ctx.authz.canIssueInvite(inv.org_id, inv.created_by, inv.role)) return;
    // A ban placed while the account was being set up keeps them out.
    if (this.ctx.authz.isBanned(inv.org_id, userId)) return;
    const joined = joinOrgByInvite(this.ctx, inv, userId, t, false);
    this.ctx.audit({ actorId: userId, orgId: joined.orgId, action: "invite.accept", target: joined.inviteId, detail: joined.role });
  }

  private async addDevice(s: ActiveSession, a: AccountRow, userId: string, body: z.infer<typeof enrollDeviceBody>): Promise<EnrollResponse> {
    const user = this.ctx.db.prepare("SELECT username, identity_key FROM users WHERE id = ?").get(userId) as { username: string; identity_key: string };
    if (body.identityKey && body.identityKey !== user.identity_key) {
      throw new ApiError(409, "identity_mismatch", "This account already has encryption keys. Restore them with your recovery key or from another device, or start fresh.");
    }
    requireKeys(body.device, user.identity_key);
    const known = this.ctx.db.prepare("SELECT id, user_id, revoked_at FROM devices WHERE signing_key = ?").get(body.device.signingKey) as
      | { id: string; user_id: string; revoked_at: number | null }
      | undefined;
    if (known && (known.user_id !== userId || known.revoked_at)) {
      throw new ApiError(409, "device_key_retired", "These device keys can't be used again. The app will make new ones.");
    }
    requireProof(this.ctx, "add-device", body.device, body.nonce, body.signature);
    if (known) {
      await this.accounts.store.bindSessionDevice(s.id, known.id);
      return { userId, deviceId: known.id, username: user.username, result: "existing" };
    }
    const max = this.ctx.config.abuse.maxDevicesPerUser;
    const active = (this.ctx.db.prepare("SELECT COUNT(*) n FROM devices WHERE user_id = ? AND revoked_at IS NULL").get(userId) as { n: number }).n;
    if (max > 0 && active >= max) {
      throw new ApiError(409, "too_many_devices", "This account has as many devices as it can have. Remove one you no longer use (Settings › Devices).");
    }
    const t = now();
    const deviceId = await this.accounts.store.bindDeviceWith(s.id, () => insertDevice(this.ctx, userId, body.device, t));
    this.ctx.audit({ actorId: userId, action: "device.add", target: deviceId, detail: `account:${a.id}` });
    notifyKeyChange(this.ctx, userId);
    return { userId, deviceId, username: user.username, result: "device" };
  }

  /**
   * "Start fresh" for someone with neither their recovery key nor another device: a new identity key replaces the old
   * one, every other device is removed, and the server history encrypted under the old key (which nobody can read any
   * more) is deleted. Contacts see a new security code. Needs the password again.
   */
  async resetIdentity(s: ActiveSession, body: z.infer<typeof resetIdentityBody>, ip: string, reply: { header: (k: string, v: string) => unknown }): Promise<EnrollResponse> {
    const a = (await this.accounts.store.accountById(s.account_id))!;
    await this.accounts.checkPassword(a, body.password, ip, reply);
    if (!a.user_id) return this.createIdentity(s, a, { ...body, username: undefined });
    const userId = a.user_id;
    requireKeys(body.device, body.identityKey);
    requireProof(this.ctx, "register", body.device, body.nonce, body.signature);
    const t = now();
    const others = (this.ctx.db.prepare("SELECT id FROM devices WHERE user_id = ? AND revoked_at IS NULL").all(userId) as { id: string }[]).map((r) => r.id);
    let deviceId: string;
    try {
      // The session is bound to the new device with the same step, so it isn't ended with the old devices if it was on
      // one of them.
      deviceId = await this.accounts.store.bindDeviceWith(s.id, () => {
        this.ctx.db.prepare("UPDATE users SET identity_key = ?, updated_at = ? WHERE id = ?").run(body.identityKey, t, userId);
        const id = insertDevice(this.ctx, userId, body.device, t);
        for (const d of others) revokeDeviceRows(this.ctx, d, t);
        this.ctx.db.prepare("DELETE FROM history_items WHERE user_id = ?").run(userId);
        this.ctx.db.prepare("DELETE FROM history_accounts WHERE user_id = ?").run(userId);
        return id;
      });
    } catch (e) {
      throw uniqueConflict(e);
    }
    await this.accounts.revokeSessions(a.id, "identity_reset", s.id);
    for (const d of others) this.ctx.hub.disconnectDevice(d, "encryption reset");
    this.ctx.audit({ actorId: userId, action: "identity.reset", target: userId, detail: `devices:${others.length}` });
    notifyKeyChange(this.ctx, userId);
    await evictUserDevicesFromCalls(this.ctx, userId);
    const username = (this.ctx.db.prepare("SELECT username FROM users WHERE id = ?").get(userId) as { username: string }).username;
    return { userId, deviceId, username, result: "identity" };
  }
}
