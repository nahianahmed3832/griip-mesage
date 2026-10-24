import type { FastifyInstance } from "fastify";
import {
  AdminAccountParams,
  AdminAuditQuery,
  AdminConnectorParams,
  AdminGrantBody,
  AdminIncidentCreate,
  AdminIncidentNote,
  AdminIncidentParams,
  AdminIncidentPatch,
  AdminIncidentsQuery,
  AdminLimitsBody,
  AdminOrgParams,
  AdminReasonBody,
  AdminRoleBody,
  AdminSessionParams,
} from "../../shared/contracts/http/platformAdmin.js";
import { parse } from "../../shared/validation/index.js";
import { AdminAdmins } from "./admins.js";
import { AdminAuditView } from "./auditView.js";
import { AdminBilling } from "./billing.js";
import type { AdminDeps } from "./dataRoutes.js";
import { metaOf, principal, requirePermission } from "./guards.js";
import { AdminIncidents } from "./incidents.js";
import { AdminInfrastructure } from "./infrastructure.js";
import type { Permission } from "./permissions.js";

/** Billing status and limits, infrastructure, incidents, the audit viewer and admin management (phase 4). */
export default async function adminOpsRoutes(app: FastifyInstance, d: AdminDeps) {
  const needs = (permission: Permission) => ({ preHandler: requirePermission(d.auth, d.audit, permission) });
  const billing = new AdminBilling(d.ctx, d.audit);
  const infra = new AdminInfrastructure(d.ctx, d.audit, d.version);
  const incidents = new AdminIncidents(d.ctx, d.store, d.audit);
  const auditView = new AdminAuditView(d.ctx, d.audit);
  const admins = new AdminAdmins(d.ctx, d.store, d.audit);

  // ── Entitlements and billing status ──
  app.get("/admin/v1/entitlements", needs("entitlements.view"), async () => billing.overview());
  app.put("/admin/v1/orgs/:orgId/limits", needs("entitlements.edit"), async (req) => {
    const { orgId } = parse(AdminOrgParams, req.params);
    const { limits, reason } = parse(AdminLimitsBody, req.body);
    return billing.setLimits(principal(req), orgId, limits, reason, metaOf(req));
  });

  // ── Infrastructure ──
  app.get("/admin/v1/infrastructure", needs("health.view"), async () => infra.report());
  app.post("/admin/v1/connectors/:id/check", needs("connectors.check"), async (req) => {
    const { id } = parse(AdminConnectorParams, req.params);
    return infra.check(principal(req), id, metaOf(req));
  });

  // ── Incidents ──
  app.get("/admin/v1/incidents", needs("incidents.view"), async (req) => incidents.list(parse(AdminIncidentsQuery, req.query)));
  app.post("/admin/v1/incidents", needs("incidents.manage"), async (req) => incidents.create(principal(req), parse(AdminIncidentCreate, req.body), metaOf(req)));
  app.get("/admin/v1/incidents/assignees", needs("incidents.manage"), async () => incidents.assignees());
  app.get("/admin/v1/incidents/:incidentId", needs("incidents.view"), async (req) => incidents.detail(parse(AdminIncidentParams, req.params).incidentId));
  app.patch("/admin/v1/incidents/:incidentId", needs("incidents.manage"), async (req) => {
    const { incidentId } = parse(AdminIncidentParams, req.params);
    return incidents.update(principal(req), incidentId, parse(AdminIncidentPatch, req.body), metaOf(req));
  });
  app.post("/admin/v1/incidents/:incidentId/notes", needs("incidents.manage"), async (req) => {
    const { incidentId } = parse(AdminIncidentParams, req.params);
    return incidents.note(principal(req), incidentId, parse(AdminIncidentNote, req.body).body, metaOf(req));
  });

  // ── Audit ──
  app.get("/admin/v1/audit", needs("audit.view"), async (req) => auditView.search(parse(AdminAuditQuery, req.query)));
  app.get("/admin/v1/audit/verify", needs("audit.view"), async () => auditView.verify());

  // ── Admins ──
  app.get("/admin/v1/admins", needs("admins.view"), async () => admins.list());
  app.post("/admin/v1/admins", needs("admins.manage"), async (req) => {
    const { email, role, reason } = parse(AdminGrantBody, req.body);
    return admins.grant(principal(req), email, role, reason, metaOf(req));
  });
  app.patch("/admin/v1/admins/:accountId", needs("admins.manage"), async (req) => {
    const { accountId } = parse(AdminAccountParams, req.params);
    const { role, reason } = parse(AdminRoleBody, req.body);
    return admins.changeRole(principal(req), accountId, role, reason, metaOf(req));
  });
  app.post("/admin/v1/admins/:accountId/revoke", needs("admins.manage"), async (req) => {
    const { accountId } = parse(AdminAccountParams, req.params);
    return admins.revoke(principal(req), accountId, parse(AdminReasonBody, req.body).reason, metaOf(req));
  });
  app.post("/admin/v1/admins/:accountId/reset-passkeys", needs("admins.manage"), async (req) => {
    const { accountId } = parse(AdminAccountParams, req.params);
    return admins.resetPasskeys(principal(req), accountId, parse(AdminReasonBody, req.body).reason, metaOf(req));
  });
  app.get("/admin/v1/admins/:accountId/sessions", needs("admins.view"), async (req) => admins.sessions(principal(req), parse(AdminAccountParams, req.params).accountId));
  app.post("/admin/v1/admin-sessions/:sessionId/revoke", needs("admins.manage"), async (req) => {
    const { sessionId } = parse(AdminSessionParams, req.params);
    return admins.revokeSession(principal(req), sessionId, parse(AdminReasonBody, req.body).reason, metaOf(req));
  });
}
