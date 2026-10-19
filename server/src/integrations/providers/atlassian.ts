import { connectorDef } from "../catalog.js";
import type { ActionInput, CallContext, Connector, ConnectorDeps } from "../connector.js";
import { ApiError } from "../../util.js";
import { ProviderError } from "../http.js";
import type { AccountIdentity, Resource } from "../types.js";
import { oauth2Provider, quoteEscape, str, ts } from "./oauth2.js";

/**
 * Jira Cloud through Atlassian OAuth 2.0 (3LO) (docs/INTEGRATIONS.md › Jira). Calls go to
 * api.atlassian.com/ex/jira/<cloud id>/rest/api/3 as the person, so Jira's own permissions apply. Atlassian rotates
 * refresh tokens: every refresh stores the new one. Creating issues and comments are separate capabilities, off unless an
 * admin grants them, and only run when the person confirms. Jira notifications into channels are planned (dynamic
 * webhooks expire after 30 days and their signing isn't documented well enough to rely on yet).
 */
const API = "https://api.atlassian.com";
const PAGE_SIZE = 20;
const FIELDS = ["summary", "status", "assignee", "updated", "issuetype", "project"];
const ISSUE_KEY = /^[A-Z][A-Z0-9_]{0,9}-\d{1,9}$/;

interface JiraIssue {
  key?: string;
  fields?: {
    summary?: string;
    status?: { name?: string };
    assignee?: { displayName?: string } | null;
    updated?: string;
    issuetype?: { name?: string };
    project?: { key?: string; name?: string };
  };
}

function jira(ctx: CallContext, path: string): string {
  const cloud = ctx.meta.cloudId;
  if (!cloud || !/^[0-9a-f-]{36}$/i.test(cloud)) throw new ProviderError("not_found", "This Jira connection has no site; connect again");
  return `${API}/ex/jira/${cloud}/rest/api/3${path}`;
}

function issueResource(ctx: CallContext, i: JiraIssue): Resource {
  if (!i.key) throw new ProviderError("bad_response", "Jira sent an issue without a key");
  const site = ctx.meta.siteUrl;
  return {
    id: i.key,
    kind: "issue",
    title: `${i.key} ${i.fields?.summary ?? ""}`.trim(),
    url: site ? `${site}/browse/${i.key}` : null,
    status: str(i.fields?.status?.name),
    detail: str(i.fields?.assignee?.displayName),
    container: str(i.fields?.project?.name),
    modifiedAt: ts(i.fields?.updated),
  };
}

/** Atlassian Document Format for plain text: one paragraph per line. */
function adf(text: string) {
  return {
    type: "doc",
    version: 1,
    content: text.split("\n").map((line) => ({ type: "paragraph", content: line ? [{ type: "text", text: line }] : [] })),
  };
}

function requireText(input: ActionInput, field: string, max: number, required = true): string | null {
  const v = (input.fields[field] ?? "").trim();
  if (!v && required) throw new ApiError(400, "bad_request", `Fill in ${field}`);
  if (v.length > max) throw new ApiError(400, "bad_request", `${field} is too long (at most ${max} characters)`);
  return v || null;
}

export function jiraConnector(deps: ConnectorDeps): Connector {
  const cfg = deps.config.atlassian;
  return {
    def: connectorDef("jira")!,
    oauth: cfg
      ? oauth2Provider(
          {
            label: "Jira",
            authorizeEndpoint: "https://auth.atlassian.com/authorize",
            tokenEndpoint: "https://auth.atlassian.com/oauth/token",
            clientId: cfg.clientId,
            clientSecret: cfg.clientSecret,
            pkce: false,
            clientAuth: "body",
            tokenFormat: "json",
            scopeSeparator: " ",
            authorizeExtras: { audience: "api.atlassian.com", prompt: "consent" },
            now: deps.now,
          },
          deps.fetch,
        )
      : null,
    async identify(ctx): Promise<AccountIdentity> {
      const me = await ctx.api<{ account_id?: string; email?: string; name?: string }>(`${API}/me`);
      if (!me?.account_id) throw new ProviderError("bad_response", "Atlassian didn't say whose account this is");
      // The Jira site this grant covers (the first one with Jira scopes; most people have one).
      const sites = await ctx.api<{ id?: string; url?: string; name?: string; scopes?: string[] }[]>(`${API}/oauth/token/accessible-resources`);
      const site = (sites ?? []).find((s) => s.id && (s.scopes ?? []).some((x) => x.includes("jira")));
      if (!site?.id) throw new ProviderError("forbidden", "This Atlassian account has no Jira site Meldiq can use");
      const meta: Record<string, string> = { cloudId: site.id };
      if (site.url && /^https:\/\/[a-z0-9-]+\.atlassian\.net$/i.test(site.url)) meta.siteUrl = site.url;
      if (site.name) meta.siteName = site.name;
      return { externalId: me.account_id, label: me.email ?? me.name ?? null, meta };
    },
    search: {
      capability: "issues.search",
      async run(ctx, query, cursor) {
        const q = query.trim().slice(0, 200);
        const jql = ISSUE_KEY.test(q.toUpperCase())
          ? `key = "${q.toUpperCase()}"`
          : q
            ? `text ~ "${quoteEscape(q, '"')}" ORDER BY updated DESC`
            : "(assignee = currentUser() OR reporter = currentUser()) ORDER BY updated DESC";
        const r = await ctx.api<{ issues?: JiraIssue[]; nextPageToken?: string }>(jira(ctx, "/search/jql"), {
          method: "POST",
          json: { jql, maxResults: PAGE_SIZE, fields: FIELDS, ...(cursor ? { nextPageToken: cursor } : {}) },
        });
        return { items: (r?.issues ?? []).map((i) => issueResource(ctx, i)), cursor: r?.nextPageToken ?? null };
      },
    },
    get: {
      capability: "links.unfurl",
      async run(ctx, id) {
        // "KEY-1", or "<site>.atlassian.net/KEY-1" from a link: then only on the person's own site (the same key on
        // another site is another issue, which their connection can't see).
        const at = /^([a-z0-9-]+\.atlassian\.net)\/(.+)$/.exec(id);
        const key = at ? at[2] : id;
        if (at && `https://${at[1]}` !== (ctx.meta.siteUrl ?? "").toLowerCase()) throw new ProviderError("not_found", "That issue is on another Jira site");
        if (!ISSUE_KEY.test(key)) throw new ProviderError("not_found", "That isn't a Jira issue key");
        return issueResource(ctx, await ctx.api<JiraIssue>(jira(ctx, `/issue/${key}`), { query: { fields: FIELDS.join(",") } }));
      },
    },
    unfurlCapability: "links.unfurl",
    matchUrl(url) {
      if (url.protocol !== "https:" || !/^[a-z0-9-]+\.atlassian\.net$/i.test(url.hostname)) return null;
      const m = /^\/browse\/([A-Z][A-Z0-9_]{0,9}-\d{1,9})\/?$/.exec(url.pathname);
      return m ? `${url.hostname.toLowerCase()}/${m[1]}` : null;
    },
    actions: {
      create_issue: {
        capability: "issues.create",
        label: "Create a Jira issue",
        async run(ctx, input) {
          const project = requireText(input, "projectKey", 10)!.toUpperCase();
          if (!/^[A-Z][A-Z0-9_]{0,9}$/.test(project)) throw new ApiError(400, "bad_request", "Use a project key such as ENG");
          const summary = requireText(input, "summary", 255)!;
          const description = requireText(input, "description", 5000, false);
          const type = requireText(input, "issueType", 40, false) ?? "Task";
          const r = await ctx.api<{ key?: string }>(jira(ctx, "/issue"), {
            method: "POST",
            json: { fields: { project: { key: project }, summary, issuetype: { name: type }, ...(description ? { description: adf(description) } : {}) } },
          });
          if (!r?.key) throw new ProviderError("bad_response", "Jira didn't return the new issue");
          return issueResource(ctx, await ctx.api<JiraIssue>(jira(ctx, `/issue/${r.key}`), { query: { fields: FIELDS.join(",") } }));
        },
      },
      comment: {
        capability: "issues.comment",
        label: "Comment on a Jira issue",
        async run(ctx, input) {
          const key = (input.resourceId ?? "").toUpperCase();
          if (!ISSUE_KEY.test(key)) throw new ProviderError("not_found", "That isn't a Jira issue key");
          const text = requireText(input, "text", 5000)!;
          await ctx.api(jira(ctx, `/issue/${key}/comment`), { method: "POST", json: { body: adf(text) } });
          return issueResource(ctx, await ctx.api<JiraIssue>(jira(ctx, `/issue/${key}`), { query: { fields: FIELDS.join(",") } }));
        },
      },
    },
  };
}
