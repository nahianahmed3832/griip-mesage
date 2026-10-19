import { connectorDef } from "../catalog.js";
import type { CallContext, Connector, ConnectorDeps } from "../connector.js";
import { ProviderError, providerJson } from "../http.js";
import type { Resource } from "../types.js";
import { metaOf, num, oauth2Provider, str, ts } from "./oauth2.js";

/**
 * GitLab (gitlab.com, or a self-managed instance at GITLAB_BASE_URL) through its REST API v4 with an OAuth token of the
 * `read_user` and `read_api` scopes (docs/INTEGRATIONS.md › GitLab; docs.gitlab.com/api/oauth2, checked 2026-10-19):
 * searching the issues and merge requests the person can see, and previews of issue and merge request links, read-only
 * and as the person. GitLab has no narrower read scope than `read_api`; Meldiq only calls the endpoints below.
 * Channel notifications (project webhooks) are planned; today a project webhook can post to a Meldiq incoming webhook.
 */
const PAGE_SIZE = 10;
/** A project path: groups and subgroups, then the project ("group/sub/project"). */
const PROJECT_PATH = /^[A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+){1,19}$/;
const LINK = /^\/((?:[A-Za-z0-9_.-]+\/){1,19}[A-Za-z0-9_.-]+)\/-\/(issues|merge_requests)\/(\d{1,9})(?:\/|$)/;

interface GitLabItem {
  iid?: number;
  title?: string;
  state?: string;
  web_url?: string;
  updated_at?: string;
  author?: { name?: string; username?: string };
  references?: { full?: string };
}

type Kind = "issues" | "merge_requests";

/** "issue:group/project:12" / "mr:group/project:7": what Meldiq stores and asks for again. */
function idOf(kind: Kind, path: string, iid: number): string {
  return `${kind === "issues" ? "issue" : "mr"}:${path}:${iid}`;
}

function parseId(id: string): { kind: Kind; path: string; iid: number } | null {
  const m = /^(issue|mr):(.+):(\d{1,9})$/.exec(id);
  if (!m || !PROJECT_PATH.test(m[2])) return null;
  return { kind: m[1] === "issue" ? "issues" : "merge_requests", path: m[2], iid: Number(m[3]) };
}

/** The project path from an item's web URL on this instance (search answers carry no path otherwise). */
function pathFrom(webUrl: string | undefined, base: string): string | null {
  if (!webUrl?.startsWith(`${base}/`)) return null;
  const m = LINK.exec(new URL(webUrl).pathname);
  return m ? m[1] : null;
}

function resource(kind: Kind, item: GitLabItem, base: string, path: string | null): Resource {
  const iid = num(item.iid);
  const where = path ?? pathFrom(item.web_url, base);
  if (iid === null || !where) throw new ProviderError("bad_response", "GitLab sent an item without its project or number");
  const ref = item.references?.full ?? `${where}${kind === "issues" ? "#" : "!"}${iid}`;
  return {
    id: idOf(kind, where, iid),
    kind: kind === "issues" ? "issue" : "pull_request",
    title: `${ref} ${item.title ?? ""}`.trim(),
    url: str(item.web_url),
    status: str(item.state),
    detail: str(item.author?.name) ?? str(item.author?.username),
    container: where,
    modifiedAt: ts(item.updated_at),
  };
}

export function gitlabConnector(deps: ConnectorDeps): Connector {
  const cfg = deps.config.gitlab;
  const base = cfg?.baseUrl ?? "https://gitlab.com";
  const api = `${base}/api/v4`;
  const list = async (ctx: CallContext, kind: Kind, q: string, page: number): Promise<GitLabItem[]> => {
    const u = new URL(`${api}/${kind}`);
    u.searchParams.set("scope", "all");
    u.searchParams.set("state", "opened");
    u.searchParams.set("order_by", "updated_at");
    u.searchParams.set("per_page", String(PAGE_SIZE));
    u.searchParams.set("page", String(page));
    if (q) u.searchParams.set("search", q);
    else u.searchParams.set("scope", "assigned_to_me");
    return (await ctx.api<GitLabItem[]>(u.toString())) ?? [];
  };
  return {
    def: connectorDef("gitlab")!,
    oauth: cfg
      ? oauth2Provider(
          {
            label: "GitLab",
            authorizeEndpoint: `${base}/oauth/authorize`,
            tokenEndpoint: `${base}/oauth/token`,
            clientId: cfg.clientId,
            clientSecret: cfg.clientSecret,
            pkce: true,
            clientAuth: "body",
            tokenFormat: "form",
            scopeSeparator: " ",
            // GitLab rotates refresh tokens on every refresh (the new one replaces the old).
            revoke: async ({ accessToken }) => {
              await providerJson(deps.fetch, `${base}/oauth/revoke`, { method: "POST", form: { client_id: cfg.clientId, client_secret: cfg.clientSecret, token: accessToken } }, "GitLab");
            },
            now: deps.now,
          },
          deps.fetch,
        )
      : null,
    async identify(ctx) {
      const me = await ctx.api<{ id?: number; username?: string; email?: string; public_email?: string }>(`${api}/user`);
      const id = num(me?.id);
      if (id === null) throw new ProviderError("bad_response", "GitLab didn't say whose account this is");
      return { externalId: String(id), label: str(me?.email) ?? str(me?.public_email) ?? str(me?.username), meta: metaOf({ username: me?.username }) };
    },
    search: {
      capability: "issues.search",
      async run(ctx, query, cursor) {
        const q = query.trim().slice(0, 200);
        const page = cursor && /^\d{1,4}$/.test(cursor) ? Number(cursor) : 1;
        const [issues, mrs] = await Promise.all([list(ctx, "issues", q, page), list(ctx, "merge_requests", q, page)]);
        const items = [...issues.map((i) => resource("issues", i, base, null)), ...mrs.map((m) => resource("merge_requests", m, base, null))]
          .sort((a, b) => (b.modifiedAt ?? 0) - (a.modifiedAt ?? 0));
        const more = issues.length === PAGE_SIZE || mrs.length === PAGE_SIZE;
        return { items, cursor: more ? String(page + 1) : null };
      },
    },
    get: {
      capability: "links.unfurl",
      async run(ctx, id) {
        const p = parseId(id);
        if (!p) throw new ProviderError("not_found", "That isn't a GitLab issue or merge request");
        const item = await ctx.api<GitLabItem>(`${api}/projects/${encodeURIComponent(p.path)}/${p.kind}/${p.iid}`);
        if (!item?.iid) throw new ProviderError("not_found", "GitLab couldn't find that item, or you can't access it");
        return resource(p.kind, item, base, p.path);
      },
    },
    unfurlCapability: "links.unfurl",
    matchUrl(url) {
      if (url.protocol !== "https:" || `${url.protocol}//${url.host}` !== base) return null;
      const m = LINK.exec(url.pathname);
      if (!m || !PROJECT_PATH.test(m[1])) return null;
      return idOf(m[2] as Kind, m[1], Number(m[3]));
    },
  };
}
