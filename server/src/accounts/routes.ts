import type { FastifyInstance, FastifyRequest } from "fastify";
import { z } from "zod";
import { type AppContext, bearer, parse } from "../context.js";
import { removePushEndpoint } from "../push/service.js";
import { notFound, now, unauthorized } from "../util.js";
import type { Accounts } from "./index.js";
import {
  changePasswordBody,
  emailBody,
  enrollDeviceBody,
  legacyCompleteBody,
  legacyVerifyBody,
  loginBody,
  preferencesBody,
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
  const { service, identity, legacy, workspaces, store } = accounts;
  // Unauthenticated routes that hash passwords or send email: tighter than the global limit, per network.
  const strict = { config: { rateLimit: { max: ctx.config.accounts.authRateLimitPerMinute, timeWindow: "1 minute" } } };
  const requireAccount = async (req: FastifyRequest) => {
    const s = service.sessions.authenticate(bearer(req), now());
    if (!s) throw unauthorized();
    req.accountSession = s;
  };
  const authed = { preHandler: requireAccount };
  const authedStrict = { preHandler: requireAccount, ...strict };
  const accountOf = (req: FastifyRequest) => store.accountById(session(req).account_id)!;

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
    service.logout(s);
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

  // ───────────── The signed-in account ─────────────
  app.get("/v2/account", authed, async (req) => {
    const s = session(req);
    return { account: service.view(accountOf(req)), session: { id: s.id, deviceId: s.device_id, createdAt: s.created_at } };
  });

  app.patch("/v2/account", authed, async (req) => {
    const { name } = parse(updateAccountBody, req.body);
    const a = accountOf(req);
    const t = now();
    store.transaction(() => {
      store.updateAccount(a.id, { name }, t);
      // The encryption identity shows the same name to contacts.
      if (a.user_id) ctx.db.prepare("UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?").run(name, t, a.user_id);
    });
    return { account: service.view(store.accountById(a.id)!) };
  });

  app.post("/v2/account/password", authedStrict, async (req, reply) => {
    const body = parse(changePasswordBody, req.body);
    await service.changePassword(session(req), body.currentPassword, body.newPassword, req.ip, reply);
    return { ok: true };
  });

  app.get("/v2/account/sessions", authed, async (req) => {
    const s = session(req);
    return {
      sessions: store.activeSessions(s.account_id, now()).map((r) => ({
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
    const target = store.sessionById(id);
    // Someone else's session answers like one that doesn't exist.
    if (!target || target.account_id !== s.account_id || target.revoked_at) throw notFound("Session not found");
    service.logout(target);
    ctx.audit({ actorId: target.user_id, action: "account.session_revoke", target: id, detail: id === s.id ? "self" : null });
    return { ok: true };
  });

  app.put("/v2/account/preferences", authed, async (req) => {
    const { selectedWorkspaceId } = parse(preferencesBody, req.body);
    return { selectedWorkspaceId: workspaces.select(accountOf(req), selectedWorkspaceId) };
  });

  // ───────────── Encryption setup on this device ─────────────
  app.post("/v2/account/devices", authedStrict, async (req) => identity.enroll(session(req), parse(enrollDeviceBody, req.body)));

  app.post("/v2/account/identity/reset", authedStrict, async (req, reply) => identity.resetIdentity(session(req), parse(resetIdentityBody, req.body), req.ip, reply));

  // ───────────── Workspaces ─────────────
  app.get("/v2/workspaces", authed, async (req) => workspaces.list(accountOf(req)));

  // ───────────── Legacy migration ─────────────
  app.post("/v2/migration/legacy/verify", strict, async (req, reply) => legacy.verify(parse(legacyVerifyBody, req.body), req.ip, reply));

  app.post("/v2/migration/legacy/complete", strict, async (req, reply) => {
    const { email } = await legacy.complete(parse(legacyCompleteBody, req.body));
    return reply.code(202).send({ status: "verification_sent", email });
  });
}
