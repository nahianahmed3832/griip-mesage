import { connectorDef } from "../catalog.js";
import type { Connector, ConnectorDeps } from "../connector.js";
import { ProviderError, providerJson } from "../http.js";
import type { Resource } from "../types.js";
import { metaOf, oauth2Provider, str, ts } from "./oauth2.js";

/**
 * Asana through its REST API with OAuth scopes `users:read tasks:read projects:read` (docs/INTEGRATIONS.md › Asana;
 * developers.asana.com/docs/oauth, checked 2026-10-19): link previews of task links with the viewer's own access.
 * Authorisation at app.asana.com/-/oauth_authorize, tokens at /-/oauth_token (client id and secret in the form body,
 * PKCE S256), refresh tokens are long-lived and revoked at /-/oauth_revoke. Task search needs Asana's paid search API,
 * so nothing is searched; project updates in channels are planned, not built.
 */
const API = "https://app.asana.com/api/1.0";
const GID = /^\d{1,32}$/;
const TASK_FIELDS = "name,completed,assignee.name,memberships.project.name,permalink_url,modified_at,due_on";
/** Task links: the classic /0/<project>/<task>(/f) and the newer /1/<workspace>/project/<project>/task/<task>. */
const CLASSIC = /^\/0\/\d{1,32}\/(\d{1,32})(?:\/f)?\/?$/;
const NEWER = /^\/1\/\d{1,32}\/(?:project\/\d{1,32}\/)?task\/(\d{1,32})(?:\/|$)/;

interface AsanaTask {
  gid?: string;
  name?: string;
  completed?: boolean;
  assignee?: { name?: string } | null;
  memberships?: { project?: { name?: string } }[];
  permalink_url?: string;
  modified_at?: string;
  due_on?: string | null;
}

function taskResource(t: AsanaTask): Resource {
  if (!t.gid || !t.name) throw new ProviderError("bad_response", "Asana sent a task without a name");
  return {
    id: t.gid,
    kind: "issue",
    title: t.name,
    url: str(t.permalink_url),
    status: t.completed ? "Completed" : t.due_on ? `Due ${t.due_on}` : "Open",
    detail: str(t.assignee?.name),
    container: str(t.memberships?.[0]?.project?.name),
    modifiedAt: ts(t.modified_at),
  };
}

export function asanaConnector(deps: ConnectorDeps): Connector {
  const cfg = deps.config.asana;
  return {
    def: connectorDef("asana")!,
    oauth: cfg
      ? oauth2Provider(
          {
            label: "Asana",
            authorizeEndpoint: "https://app.asana.com/-/oauth_authorize",
            tokenEndpoint: "https://app.asana.com/-/oauth_token",
            clientId: cfg.clientId,
            clientSecret: cfg.clientSecret,
            pkce: true,
            clientAuth: "body",
            tokenFormat: "form",
            scopeSeparator: " ",
            // Asana revokes by refresh token (that also ends its access tokens).
            revoke: async ({ refreshToken }) => {
              if (!refreshToken) return;
              await providerJson(deps.fetch, "https://app.asana.com/-/oauth_revoke", { method: "POST", form: { client_id: cfg.clientId, client_secret: cfg.clientSecret, token: refreshToken } }, "Asana");
            },
            now: deps.now,
          },
          deps.fetch,
        )
      : null,
    async identify(ctx) {
      const r = await ctx.api<{ data?: { gid?: string; name?: string; email?: string } }>(`${API}/users/me?opt_fields=name,email`);
      const me = r?.data;
      if (!me?.gid) throw new ProviderError("bad_response", "Asana didn't say whose account this is");
      return { externalId: me.gid, label: me.email ?? me.name ?? null, meta: metaOf({ name: me.name }) };
    },
    get: {
      capability: "links.unfurl",
      async run(ctx, id) {
        if (!GID.test(id)) throw new ProviderError("not_found", "That isn't an Asana task");
        const r = await ctx.api<{ data?: AsanaTask }>(`${API}/tasks/${id}?opt_fields=${TASK_FIELDS}`);
        if (!r?.data) throw new ProviderError("not_found", "Asana couldn't find that task, or you can't access it");
        return taskResource(r.data);
      },
    },
    unfurlCapability: "links.unfurl",
    matchUrl(url) {
      if (url.protocol !== "https:" || url.hostname !== "app.asana.com") return null;
      return CLASSIC.exec(url.pathname)?.[1] ?? NEWER.exec(url.pathname)?.[1] ?? null;
    },
  };
}
