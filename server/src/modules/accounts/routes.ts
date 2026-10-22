import type { FastifyInstance, FastifyRequest } from "fastify";
import { z } from "zod";
import { type AppContext, bearer, parse } from "../../app/context.js";
import { removePushEndpoint } from "../notifications/service.js";
import { notFound, now, unauthorized } from "../../shared/utilities/util.js";
import { DELETE_ACCOUNT_RATE_LIMIT, deleteOwnAccount } from "./deletion.js";
import type { Accounts } from "./index.js";
import {
  changePasswordBody,
  deleteAccountBody,
  emailBody,
  enrollDeviceBody,
  keyUnlockBody,
  legacyCompleteBody,
  legacyVerifyBody,
  loginBody,
  preferencesBody,
  putKeyBackupBody,
  recoveryCompleteBody,
  recoveryPasswordBody,
  recoveryStartBody,
  refreshBody,
  registerBody,
  resetIdentityBody,
  resetPasswordBody,
  updateAccountBody,
  verifyEmailBody,
} from "./schemas.js";
import type { ActiveSession } from "./store.js";

declare module "fastify" {
  interface FastifyRequest {
    accountSession?: ActiveSession;
  }
}

function session(req: FastifyRequest): ActiveSession {
  if (!req.accountSession) throw unauthorized();
  return req.accountSession;
}

/** The v2 account API (docs/ORBIT_2_ARCHITECTURE §6). */
export default async function accountRoutes(app: FastifyInstance, ctx: AppContext, accounts: Accounts) {
  const { service, identity, legacy, workspaces, store, recovery, keyBackups } = accounts;
  // Unauthenticated routes that hash passwords or send email: tighter than the global limit, per network.
  const strict = { config: { rateLimit: { max: ctx.config.accounts.authRateLimitPerMinute, timeWindow: "1 minute" } } };
  const requireAccount = async (req: FastifyRequest) => {
    const s = await service.sessions.authenticate(bearer(req), now());
    if (!s) throw unauthorized();
    req.accountSession = s;
  };
  const authed = { preHandler: requireAccount };
  const authedStrict = { preHandler: requireAccount, ...strict };
  const accountOf = async (req: FastifyRequest) => (await store.accountById(session(req).account_id))!;

  // ───────────── Sign-up, email codes, sign-in ─────────────
  app.post("/v2/auth/register", strict, async (req, reply) => {
    const body = parse(registerBody, req.body);
    await service.register(body, req.ip, reply);
    return reply.code(202).send({ status: "verification_sent", email: body.email });
  });

  app.post("/v2/auth/email/verify", strict, async (req) => {
    const body = parse(verifyEmailBody, req.body);
    await service.verifyEmail(body.email, body.code, body.password);
    return { verified: true };
  });

  app.post("/v2/auth/email/resend", strict, async (req, reply) => {
    await service.resendVerification(parse(emailBody, req.body).email, req.ip);
    return reply.code(202).send({ status: "sent_if_pending" });
  });

  app.post("/v2/auth/login", strict, async (req, reply) => service.login(parse(loginBody, req.body), req.ip, reply));

  // Renewal happens every few minutes from every signed-in app; phones behind one carrier NAT share an address.
  const renewal = { config: { rateLimit: { max: ctx.config.accounts.authRateLimitPerMinute * 10, timeWindow: "1 minute" } } };
  app.post("/v2/auth/refresh", renewal, async (req) => service.refresh(parse(refreshBody, req.body).refreshToken));

  app.post("/v2/auth/logout", authed, async (req) => {
    const s = session(req);
    await service.logout(s);
    // A signed-out device isn't woken any more; signing in again, the app registers its endpoint again.
    if (s.device_id) removePushEndpoint(ctx.db, s.device_id);
    return { ok: true };
  });

  app.post("/v2/auth/password/forgot", strict, async (req, reply) => {
    await service.forgotPassword(parse(emailBody, req.body).email, req.ip);
    return reply.code(202).send({ status: "sent_if_account" });
  });

  app.post("/v2/auth/password/reset", strict, async (req) => {
    const body = parse(resetPasswordBody, req.body);
    await service.resetPassword(body.email, body.code, body.newPassword);
    return { ok: true };
  });

  // ───────────── Recovery after losing the account's email (docs/ACCOUNT_RECOVERY.md) ─────────────
  app.post("/v2/auth/recover/start", strict, async (req, reply) => {
    const body = parse(recoveryStartBody, req.body);
    return recovery.start(body.email, body.code, body.newEmail, req.ip, reply);
  });

  app.post("/v2/auth/recover/complete", strict, async (req) => {
    const body = parse(recoveryCompleteBody, req.body);
    return recovery.complete(body.ticket, body.code, body.newPassword);
  });

  app.get("/v2/account/recovery-codes", authed, async (req) => recovery.status(session(req).account_id));

  app.post("/v2/account/recovery-codes", authedStrict, async (req, reply) => {
    const { password } = parse(recoveryPasswordBody, req.body);
    return recovery.generate(session(req), password, req.ip, reply);
  });

  app.post("/v2/account/recovery-codes/revoke", authedStrict, async (req, reply) => {
    const { password } = parse(recoveryPasswordBody, req.body);
    await recovery.revoke(session(req), password, req.ip, reply);
    return { ok: true };
  });

  // ───────────── The signed-in account ─────────────
  app.get("/v2/account", authed, async (req) => {
    const s = session(req);
    return { account: service.view(await accountOf(req)), session: { id: s.id, deviceId: s.device_id, createdAt: s.created_at } };
  });

  app.patch("/v2/account", authed, async (req) => {
    const { name } = parse(updateAccountBody, req.body);
    const a = await accountOf(req);
    const t = now();
    await store.updateAccount(a.id, { name }, t);
    // The encryption identity shows the same name to contacts (the same edit again repairs a half-done one).
    if (a.user_id) ctx.db.prepare("UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?").run(name, t, a.user_id);
    return { account: service.view((await store.accountById(a.id))!) };
  });

  // Deleting the account for good (docs/ACCOUNT_DELETION.md): the password again and the account's email typed out.
  // Refused with 409 owns_workspaces, changing nothing, while the person is a workspace's only owner.
  app.delete("/v2/account", { preHandler: requireAccount, config: { rateLimit: DELETE_ACCOUNT_RATE_LIMIT } }, async (req, reply) => {
    const body = parse(deleteAccountBody, req.body ?? {});
    const result = await deleteOwnAccount(ctx, accounts, await accountOf(req), body, req.ip, reply);
    return { deleted: true, deletedAt: result.deletedAt };
  });

  app.post("/v2/account/password", authedStrict, async (req, reply) => {
    const body = parse(changePasswordBody, req.body);
    if (body.keyBackup) keyBackups.requireCurrentIdentity(await accountOf(req), body.keyBackup);
    await service.changePassword(session(req), body.currentPassword, body.newPassword, req.ip, reply);
    // The old backup no longer matches the password; the keys sealed under the new one replace it.
    if (body.keyBackup) await keyBackups.save(await accountOf(req), body.keyBackup);
    return { ok: true, keyBackup: !!body.keyBackup };
  });

  // ───────────── Keys backed up under the password (docs/KEY_BACKUP.md) ─────────────
  app.get("/v2/account/key-backup", authed, async (req) => keyBackups.status(session(req)));
  app.put("/v2/account/key-backup", authedStrict, async (req, reply) => {
    const body = parse(putKeyBackupBody, req.body);
    return keyBackups.put(session(req), body.password, body.backup, req.ip, reply);
  });
  app.delete("/v2/account/key-backup", authed, async (req) => {
    await keyBackups.remove(session(req));
    return { ok: true };
  });
  app.post("/v2/account/key-backup/unlock/start", authedStrict, async (req) => keyBackups.startUnlock(session(req)));
  app.post("/v2/account/key-backup/unlock", authedStrict, async (req) => keyBackups.unlock(session(req), parse(keyUnlockBody, req.body).code));

  app.get("/v2/account/sessions", authed, async (req) => {
    const s = session(req);
    return {
      sessions: (await store.activeSessions(s.account_id, now())).map((r) => ({
        id: r.id,
        label: r.label,
        deviceId: r.device_id,
        createdAt: r.created_at,
        lastUsedAt: r.last_used_at,
        current: r.id === s.id,
      })),
    };
  });

  app.delete("/v2/account/sessions/:id", authed, async (req) => {
    const s = session(req);
    const { id } = parse(z.object({ id: z.string().min(3).max(64) }), req.params);
    const target = await store.sessionById(id);
    // Someone else's session answers like one that doesn't exist.
    if (!target || target.account_id !== s.account_id || target.revoked_at) throw notFound("Session not found");
    await service.logout(target);
    ctx.audit({ actorId: target.user_id, action: "account.session_revoke", target: id, detail: id === s.id ? "self" : null });
    return { ok: true };
  });

  app.put("/v2/account/preferences", authed, async (req) => {
    const { selectedWorkspaceId } = parse(preferencesBody, req.body);
    return { selectedWorkspaceId: await workspaces.select(await accountOf(req), selectedWorkspaceId) };
  });

  // ───────────── Encryption setup on this device ─────────────
  app.post("/v2/account/devices", authedStrict, async (req) => identity.enroll(session(req), parse(enrollDeviceBody, req.body)));

  app.post("/v2/account/identity/reset", authedStrict, async (req, reply) => identity.resetIdentity(session(req), parse(resetIdentityBody, req.body), req.ip, reply));

  // ───────────── Workspaces ─────────────
  app.get("/v2/workspaces", authed, async (req) => workspaces.list(await accountOf(req)));

  // ───────────── Legacy migration ─────────────
  app.post("/v2/migration/legacy/verify", strict, async (req, reply) => legacy.verify(parse(legacyVerifyBody, req.body), req.ip, reply));

  app.post("/v2/migration/legacy/complete", strict, async (req, reply) => {
    const { email } = await legacy.complete(parse(legacyCompleteBody, req.body));
    return reply.code(202).send({ status: "verification_sent", email });
  });
}
