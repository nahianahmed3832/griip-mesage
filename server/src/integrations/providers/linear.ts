import { connectorDef } from "../catalog.js";
import type { CallContext, Connector, ConnectorDeps } from "../connector.js";
import { ProviderError } from "../http.js";
import type { Resource } from "../types.js";
import { oauth2Provider, str, ts } from "./oauth2.js";

/**
 * Linear through its GraphQL API with an OAuth token of the "read" scope (docs/INTEGRATIONS.md › Linear): issue search
 * and link previews as the person. Creating issues and channel updates are planned, not built.
 */
const GRAPHQL = "https://api.linear.app/graphql";
const PAGE_SIZE = 20;
const ISSUE_FIELDS = "id identifier title url updatedAt state { name } assignee { name } team { name }";
const IDENT = /^[A-Z][A-Z0-9]{0,9}-\d{1,9}$/;

interface LinearIssue {
  identifier?: string;
  title?: string;
  url?: string;
  updatedAt?: string;
  state?: { name?: string };
  assignee?: { name?: string } | null;
  team?: { name?: string };
}

async function gql<T>(ctx: CallContext, query: string, variables: Record<string, unknown>): Promise<T> {
  const r = await ctx.api<{ data?: T; errors?: { message?: string; extensions?: { code?: string } }[] }>(GRAPHQL, { method: "POST", json: { query, variables } });
  if (r?.errors?.length) {
    const code = r.errors[0]?.extensions?.code ?? "";
    if (/AUTHENTICATION/i.test(code)) throw new ProviderError("unauthorized", "Linear no longer accepts this connection");
    if (/FORBIDDEN/i.test(code)) throw new ProviderError("forbidden", "Linear doesn't allow this for your account");
    throw new ProviderError("bad_response", "Linear refused the request");
  }
  if (!r?.data) throw new ProviderError("bad_response", "Linear sent an empty answer");
  return r.data;
}

function issueResource(i: LinearIssue): Resource {
  if (!i.identifier) throw new ProviderError("bad_response", "Linear sent an issue without an identifier");
  return {
    id: i.identifier,
    kind: "issue",
    title: `${i.identifier} ${i.title ?? ""}`.trim(),
    url: str(i.url),
    status: str(i.state?.name),
    detail: str(i.assignee?.name),
    container: str(i.team?.name),
    modifiedAt: ts(i.updatedAt),
  };
}

export function linearConnector(deps: ConnectorDeps): Connector {
  const cfg = deps.config.linear;
  return {
    def: connectorDef("linear")!,
    oauth: cfg
      ? oauth2Provider(
          {
            label: "Linear",
            authorizeEndpoint: "https://linear.app/oauth/authorize",
            tokenEndpoint: "https://api.linear.app/oauth/token",
            clientId: cfg.clientId,
            clientSecret: cfg.clientSecret,
            pkce: true,
            clientAuth: "body",
            tokenFormat: "form",
            scopeSeparator: ",",
            authorizeExtras: { prompt: "consent" },
            now: deps.now,
          },
          deps.fetch,
        )
      : null,
    async identify(ctx) {
      const d = await gql<{ viewer?: { id?: string; name?: string; email?: string }; organization?: { id?: string; name?: string } }>(
        ctx,
        "query { viewer { id name email } organization { id name } }",
        {},
      );
      if (!d.viewer?.id) throw new ProviderError("bad_response", "Linear didn't say whose account this is");
      const meta: Record<string, string> = {};
      if (d.organization?.id) meta.organizationId = d.organization.id;
      if (d.organization?.name) meta.organizationName = d.organization.name.slice(0, 100);
      return { externalId: d.viewer.id, label: d.viewer.email ?? d.viewer.name ?? null, meta };
    },
    search: {
      capability: "issues.search",
      async run(ctx, query, cursor) {
        const q = query.trim().slice(0, 200);
        if (!q) {
          const d = await gql<{ viewer?: { assignedIssues?: { nodes?: LinearIssue[]; pageInfo?: { hasNextPage?: boolean; endCursor?: string } } } }>(
            ctx,
            `query($after: String) { viewer { assignedIssues(first: ${PAGE_SIZE}, after: $after, orderBy: updatedAt) { nodes { ${ISSUE_FIELDS} } pageInfo { hasNextPage endCursor } } } }`,
            { after: cursor },
          );
          const c = d.viewer?.assignedIssues;
          return { items: (c?.nodes ?? []).map(issueResource), cursor: c?.pageInfo?.hasNextPage ? (c.pageInfo.endCursor ?? null) : null };
        }
        const d = await gql<{ issues?: { nodes?: LinearIssue[]; pageInfo?: { hasNextPage?: boolean; endCursor?: string } } }>(
          ctx,
          `query($q: String!, $after: String) { issues(first: ${PAGE_SIZE}, after: $after, orderBy: updatedAt, filter: { title: { containsIgnoreCase: $q } }) { nodes { ${ISSUE_FIELDS} } pageInfo { hasNextPage endCursor } } }`,
          { q, after: cursor },
        );
        return { items: (d.issues?.nodes ?? []).map(issueResource), cursor: d.issues?.pageInfo?.hasNextPage ? (d.issues.pageInfo.endCursor ?? null) : null };
      },
    },
    get: {
      capability: "links.unfurl",
      async run(ctx, id) {
        if (!IDENT.test(id)) throw new ProviderError("not_found", "That isn't a Linear issue");
        const d = await gql<{ issue?: LinearIssue }>(ctx, `query($id: String!) { issue(id: $id) { ${ISSUE_FIELDS} } }`, { id });
        if (!d.issue) throw new ProviderError("not_found", "Linear couldn't find that issue, or you can't access it");
        return issueResource(d.issue);
      },
    },
    unfurlCapability: "links.unfurl",
    matchUrl(url) {
      if (url.protocol !== "https:" || url.hostname !== "linear.app") return null;
      const m = /^\/[A-Za-z0-9_-]{1,64}\/issue\/([A-Z][A-Z0-9]{0,9}-\d{1,9})(?:\/|$)/.exec(url.pathname);
      return m ? m[1] : null;
    },
  };
}
