import { connectorDef } from "../catalog.js";
import type { CallContext, Connector, ConnectorDeps, WebhookHandler } from "../connector.js";
import { ProviderError, providerJson } from "../http.js";
import { hmacSha256Hex, safeEqual } from "../tokens.js";
import type { NormalizedEvent, Page, Resource } from "../types.js";
import { metaOf, oauth2Provider, str, ts } from "./oauth2.js";

/**
 * GitHub through a GitHub App (docs/INTEGRATIONS.md › GitHub).
 * - Members connect their own GitHub account (user-to-server token, PKCE): search issues and pull requests, preview
 *   links. The token reaches only what both the person and the app may access.
 * - An admin links the workspace to an installation of the app (on their GitHub organisation). The link is only offered
 *   for installations the admin's own token can see (GET /user/installations), so nobody can point a workspace at an
 *   installation id they merely know. Notifications then arrive as signed webhooks (X-Hub-Signature-256).
 * - Repositories are listed and checked with the admin's own token (GET /user/installations/{id}/repositories): only
 *   those both the installation and that person can read. The app's installation token is never used, so a workspace
 *   can't watch a private repository its admin can't see, even when several workspaces link the same installation.
 * No repository contents are ever read or stored.
 */
const API = "https://api.github.com";
const HEADERS = { accept: "application/vnd.github+json", "x-github-api-version": "2022-11-28" };
const PAGE_SIZE = 20;
const REPOS_PAGE_SIZE = 100;

interface Issue {
  number?: number;
  title?: string;
  html_url?: string;
  state?: string;
  updated_at?: string;
  repository_url?: string;
  user?: { login?: string };
  pull_request?: unknown;
  merged_at?: string | null;
}

const REPO = /^([A-Za-z0-9-]{1,39})\/([A-Za-z0-9._-]{1,100})$/;

function issueResource(i: Issue, repo: string): Resource {
  if (typeof i.number !== "number") throw new ProviderError("bad_response", "GitHub sent an issue without a number");
  return {
    id: `${repo}#${i.number}`,
    kind: i.pull_request ? "pull_request" : "issue",
    title: `#${i.number} ${i.title ?? ""}`.trim(),
    url: str(i.html_url),
    status: str(i.state),
    owner: str(i.user?.login),
    container: repo,
    modifiedAt: ts(i.updated_at),
  };
}

const repoOf = (url: string | undefined) => (url ? url.replace(`${API}/repos/`, "") : "");

export function github(deps: ConnectorDeps): Connector {
  const cfg = deps.config.github;
  const oauth = cfg
    ? oauth2Provider(
        {
          label: "GitHub",
          authorizeEndpoint: "https://github.com/login/oauth/authorize",
          tokenEndpoint: "https://github.com/login/oauth/access_token",
          clientId: cfg.clientId,
          clientSecret: cfg.clientSecret,
          pkce: true,
          clientAuth: "body",
          tokenFormat: "form",
          scopeSeparator: " ",
          sendScopes: false,
          authorizeExtras: { allow_signup: "false" },
          // Revokes this one token. (The grant endpoint would also end the person's connections in other workspaces.)
          revoke: async (t) => {
            await providerJson(
              deps.fetch,
              `${API}/applications/${cfg.clientId}/token`,
              {
                method: "DELETE",
                headers: { ...HEADERS, authorization: `Basic ${Buffer.from(`${cfg.clientId}:${cfg.clientSecret}`).toString("base64")}` },
                json: { access_token: t.accessToken },
              },
              "GitHub",
            );
          },
          now: deps.now,
        },
        deps.fetch,
      )
    : null;

  const api = <T>(ctx: CallContext, path: string, query?: Record<string, string | number | undefined>) => ctx.api<T>(`${API}${path}`, { headers: HEADERS, query });

  const webhook: WebhookHandler | undefined = cfg
    ? {
        eventTypes: [
          { key: "pull_request.opened", label: "Pull request opened" },
          { key: "pull_request.merged", label: "Pull request merged" },
          { key: "pull_request.closed", label: "Pull request closed without merging" },
          { key: "pull_request.ready_for_review", label: "Pull request ready for review" },
          { key: "issues.opened", label: "Issue opened" },
          { key: "issues.closed", label: "Issue closed" },
          { key: "issues.reopened", label: "Issue reopened" },
          { key: "release.published", label: "Release published" },
          { key: "workflow_run.failure", label: "Workflow run failed" },
          { key: "workflow_run.success", label: "Workflow run succeeded" },
          { key: "deployment_status.success", label: "Deployment succeeded" },
          { key: "deployment_status.failure", label: "Deployment failed" },
        ],
        verify(raw, headers) {
          const sig = headers["x-hub-signature-256"] ?? "";
          const delivery = headers["x-github-delivery"] ?? "";
          const event = headers["x-github-event"] ?? "";
          if (!/^sha256=[0-9a-f]{64}$/.test(sig) || !/^[0-9a-f-]{10,64}$/i.test(delivery) || !/^[a-z_]{1,40}$/.test(event)) return null;
          if (!safeEqual(sig, `sha256=${hmacSha256Hex(cfg.webhookSecret, raw)}`)) return null;
          return { deliveryId: delivery, event };
        },
        parse: parseGitHubEvent,
      }
    : undefined;

  return {
    def: connectorDef("github")!,
    oauth,
    async identify(ctx) {
      const u = await api<{ id?: number; login?: string }>(ctx, "/user");
      if (typeof u?.id !== "number") throw new ProviderError("bad_response", "GitHub didn't say whose account this is");
      return { externalId: String(u.id), label: u.login ?? null, meta: metaOf({ login: u.login }) };
    },
    search: {
      capability: "issues.search",
      async run(ctx, query, cursor) {
        const page = cursor && /^\d{1,3}$/.test(cursor) ? Number(cursor) : 1;
        const words = query.trim().slice(0, 200);
        // Without words: open issues and pull requests that involve the person.
        const q = words || (ctx.meta.login ? `involves:${ctx.meta.login} is:open` : "is:open author:@me");
        const r = await api<{ items?: Issue[]; total_count?: number }>(ctx, "/search/issues", { q, per_page: PAGE_SIZE, page, sort: "updated", order: "desc" });
        const list = (r?.items ?? []).map((i) => issueResource(i, repoOf(i.repository_url)));
        return { items: list, cursor: list.length === PAGE_SIZE && page < 10 ? String(page + 1) : null };
      },
    },
    get: {
      capability: "links.unfurl",
      async run(ctx, id) {
        const m = /^([^#]+)#(\d{1,9})$/.exec(id);
        if (m && REPO.test(m[1])) return issueResource(await api<Issue>(ctx, `/repos/${m[1]}/issues/${m[2]}`), m[1]);
        if (!REPO.test(id)) throw new ProviderError("not_found", "That isn't a GitHub repository, issue or pull request");
        const r = await api<{ full_name?: string; html_url?: string; description?: string; updated_at?: string; private?: boolean }>(ctx, `/repos/${id}`);
        return { id, kind: "repository", title: r?.full_name ?? id, url: str(r?.html_url), detail: str(r?.description), modifiedAt: ts(r?.updated_at), status: r?.private ? "private" : "public" };
      },
    },
    unfurlCapability: "links.unfurl",
    matchUrl(url) {
      if (url.protocol !== "https:" || url.hostname !== "github.com") return null;
      const m = /^\/([A-Za-z0-9-]{1,39})\/([A-Za-z0-9._-]{1,100})(?:\/(?:issues|pull)\/(\d{1,9}))?\/?$/.exec(url.pathname);
      if (!m) return null;
      return m[3] ? `${m[1]}/${m[2]}#${m[3]}` : `${m[1]}/${m[2]}`;
    },
    webhook,
    linking: cfg
      ? {
          async targets(ctx) {
            const r = await api<{ installations?: { id?: number; app_id?: number; account?: { login?: string; type?: string } }[] }>(ctx, "/user/installations", { per_page: 100 });
            return (r?.installations ?? [])
              .filter((i) => typeof i.id === "number" && String(i.app_id) === cfg.appId)
              .map((i) => ({ id: String(i.id), label: `${i.account?.login ?? "GitHub"}${i.account?.type === "Organization" ? " (organisation)" : ""}` }));
          },
          installUrl: () => `https://github.com/apps/${encodeURIComponent(cfg.slug)}/installations/new`,
          async resources(ctx, installationId, cursor): Promise<Page<{ id: string; label: string }>> {
            if (!/^\d{1,20}$/.test(installationId)) throw new ProviderError("not_found", "Unknown GitHub installation");
            const page = cursor && /^\d{1,3}$/.test(cursor) ? Number(cursor) : 1;
            const r = await api<{ repositories?: { full_name?: string; private?: boolean }[] }>(ctx, `/user/installations/${installationId}/repositories`, { per_page: REPOS_PAGE_SIZE, page });
            const repos = (r?.repositories ?? []).filter((x) => x.full_name).map((x) => ({ id: x.full_name!, label: `${x.full_name}${x.private ? " (private)" : ""}` }));
            return { items: repos, cursor: repos.length === REPOS_PAGE_SIZE ? String(page + 1) : null };
          },
        }
      : undefined,
  };
}

const clip = (s: string | null | undefined, n: number) => (s ? (s.length > n ? `${s.slice(0, n - 1)}…` : s) : null);

/** Turns a verified GitHub delivery into channel notifications. Unknown events and actions yield none. */
export function parseGitHubEvent(event: string, payload: unknown): { externalId: string; events: NormalizedEvent[] } | null {
  if (!payload || typeof payload !== "object") return null;
  const p = payload as Record<string, any>;
  const installation = p.installation?.id;
  if (typeof installation !== "number") return null;
  const repo = typeof p.repository?.full_name === "string" ? (p.repository.full_name as string) : null;
  const actor = clip(str(p.sender?.login), 60);
  const out: NormalizedEvent[] = [];
  const at = (v: unknown) => ts(v) ?? Date.now();
  if (repo && REPO.test(repo)) {
    if (event === "pull_request" && p.pull_request) {
      const pr = p.pull_request;
      const action = p.action === "closed" ? (pr.merged ? "merged" : "closed") : p.action;
      if (["opened", "closed", "merged", "ready_for_review", "reopened"].includes(action)) {
        out.push({
          resource: repo,
          event: `pull_request.${action}`,
          title: clip(`#${pr.number} ${pr.title ?? ""}`, 200)!,
          url: str(pr.html_url),
          summary: `Pull request ${action.replace(/_/g, " ")} in ${repo}`,
          actor,
          occurredAt: at(pr.updated_at),
          dedupeKey: `gh:pr:${repo}#${pr.number}:${action}:${pr.updated_at ?? ""}`,
        });
      }
    } else if (event === "issues" && p.issue && ["opened", "closed", "reopened"].includes(p.action)) {
      const i = p.issue;
      out.push({
        resource: repo,
        event: `issues.${p.action}`,
        title: clip(`#${i.number} ${i.title ?? ""}`, 200)!,
        url: str(i.html_url),
        summary: `Issue ${p.action} in ${repo}`,
        actor,
        occurredAt: at(i.updated_at),
        dedupeKey: `gh:issue:${repo}#${i.number}:${p.action}:${i.updated_at ?? ""}`,
      });
    } else if (event === "release" && p.action === "published" && p.release) {
      const r = p.release;
      out.push({
        resource: repo,
        event: "release.published",
        title: clip(`${r.tag_name ?? ""} ${r.name ?? ""}`.trim() || "New release", 200)!,
        url: str(r.html_url),
        summary: `Release published in ${repo}`,
        actor,
        occurredAt: at(r.published_at),
        dedupeKey: `gh:release:${repo}:${r.id}`,
      });
    } else if (event === "workflow_run" && p.action === "completed" && p.workflow_run) {
      const w = p.workflow_run;
      const result = w.conclusion === "success" ? "success" : w.conclusion === "failure" ? "failure" : null;
      if (result) {
        out.push({
          resource: repo,
          event: `workflow_run.${result}`,
          title: clip(`${w.name ?? "Workflow"} ${result === "success" ? "succeeded" : "failed"} on ${w.head_branch ?? "a branch"}`, 200)!,
          url: str(w.html_url),
          summary: `Workflow run in ${repo}`,
          actor,
          occurredAt: at(w.updated_at),
          dedupeKey: `gh:run:${repo}:${w.id}:${w.run_attempt ?? 1}:${result}`,
        });
      }
    } else if (event === "deployment_status" && p.deployment_status) {
      const d = p.deployment_status;
      const result = d.state === "success" ? "success" : d.state === "failure" || d.state === "error" ? "failure" : null;
      if (result) {
        out.push({
          resource: repo,
          event: `deployment_status.${result}`,
          title: clip(`Deployment to ${p.deployment?.environment ?? "an environment"} ${result === "success" ? "succeeded" : "failed"}`, 200)!,
          url: str(d.target_url) ?? str(p.deployment?.url ? `https://github.com/${repo}/deployments` : null),
          summary: `Deployment in ${repo}`,
          actor,
          occurredAt: at(d.updated_at),
          dedupeKey: `gh:deploy:${repo}:${d.id}:${result}`,
        });
      }
    }
  }
  return { externalId: String(installation), events: out };
}
