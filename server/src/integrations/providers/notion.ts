import { connectorDef } from "../catalog.js";
import type { Connector, ConnectorDeps } from "../connector.js";
import { ProviderError } from "../http.js";
import type { Resource } from "../types.js";
import { oauth2Provider, str, ts } from "./oauth2.js";

/**
 * Notion public integration (docs/INTEGRATIONS.md › Notion). Notion has no scopes: the integration is set to "Read
 * content" only, and at consent the person picks which pages Meldiq may see. Token requests use HTTP Basic with the
 * client credentials; refresh tokens rotate. Notion offers no revocation call here: disconnecting deletes Meldiq's copy
 * and the person removes the connection in Notion's settings.
 */
const API = "https://api.notion.com/v1";
const VERSION = "2022-06-28";
const PAGE_SIZE = 20;
const HEADERS = { "notion-version": VERSION };

interface NotionPage {
  id?: string;
  object?: string;
  url?: string;
  last_edited_time?: string;
  properties?: Record<string, { type?: string; title?: { plain_text?: string }[] }>;
}

function titleOf(p: NotionPage): string {
  for (const prop of Object.values(p.properties ?? {})) {
    if (prop?.type === "title") return (prop.title ?? []).map((t) => t.plain_text ?? "").join("").trim() || "Untitled";
  }
  return "Untitled";
}

function pageResource(p: NotionPage): Resource {
  if (!p.id) throw new ProviderError("bad_response", "Notion sent a page without an id");
  return { id: p.id.replace(/-/g, ""), kind: "page", title: titleOf(p), url: str(p.url), modifiedAt: ts(p.last_edited_time) };
}

const PAGE_ID = /^[0-9a-f]{32}$/i;

export function notionConnector(deps: ConnectorDeps): Connector {
  const cfg = deps.config.notion;
  return {
    def: connectorDef("notion")!,
    oauth: cfg
      ? oauth2Provider(
          {
            label: "Notion",
            authorizeEndpoint: `${API}/oauth/authorize`,
            tokenEndpoint: `${API}/oauth/token`,
            clientId: cfg.clientId,
            clientSecret: cfg.clientSecret,
            pkce: false,
            clientAuth: "basic",
            tokenFormat: "json",
            scopeSeparator: " ",
            sendScopes: false,
            authorizeExtras: { owner: "user" },
            tokenMeta: (raw) => {
              const meta: Record<string, string> = {};
              if (typeof raw.workspace_id === "string") meta.workspaceId = raw.workspace_id;
              if (typeof raw.workspace_name === "string") meta.workspaceName = raw.workspace_name.slice(0, 100);
              if (typeof raw.bot_id === "string") meta.botId = raw.bot_id;
              const owner = (raw.owner as { user?: { id?: string; name?: string; person?: { email?: string } } } | undefined)?.user;
              if (owner?.id) meta.ownerId = owner.id;
              if (owner?.person?.email) meta.email = owner.person.email;
              else if (owner?.name) meta.ownerName = owner.name.slice(0, 100);
              return meta;
            },
            now: deps.now,
          },
          deps.fetch,
        )
      : null,
    async identify(ctx, tokens) {
      // The bot user proves the token works; who authorised it comes with the token (or from the first connection).
      const bot = await ctx.api<{ id?: string; object?: string }>(`${API}/users/me`, { headers: HEADERS });
      if (!bot?.id) throw new ProviderError("bad_response", "Notion didn't accept the connection");
      const meta = tokens.meta ?? {};
      const externalId = meta.ownerId && meta.workspaceId ? `${meta.workspaceId}:${meta.ownerId}` : ctx.externalId || bot.id;
      const label = meta.email ?? (meta.workspaceName ? `${meta.ownerName ?? "Notion"} · ${meta.workspaceName}` : null);
      return { externalId, label, meta };
    },
    search: {
      capability: "pages.search",
      async run(ctx, query, cursor) {
        const r = await ctx.api<{ results?: NotionPage[]; next_cursor?: string | null; has_more?: boolean }>(`${API}/search`, {
          method: "POST",
          headers: HEADERS,
          json: {
            query: query.trim().slice(0, 200),
            filter: { property: "object", value: "page" },
            sort: { direction: "descending", timestamp: "last_edited_time" },
            page_size: PAGE_SIZE,
            ...(cursor ? { start_cursor: cursor } : {}),
          },
        });
        return { items: (r?.results ?? []).filter((p) => p.object === "page").map(pageResource), cursor: r?.has_more ? (r.next_cursor ?? null) : null };
      },
    },
    get: {
      capability: "pages.link",
      async run(ctx, id) {
        if (!PAGE_ID.test(id)) throw new ProviderError("not_found", "That isn't a Notion page");
        return pageResource(await ctx.api<NotionPage>(`${API}/pages/${id}`, { headers: HEADERS }));
      },
    },
    unfurlCapability: "links.unfurl",
    matchUrl(url) {
      if (url.protocol !== "https:" || !(url.hostname === "www.notion.so" || url.hostname === "notion.so" || url.hostname.endsWith(".notion.site"))) return null;
      const last = url.pathname.split("/").filter(Boolean).pop() ?? "";
      const m = /([0-9a-f]{32})$/i.exec(last.replace(/-/g, ""));
      return m ? m[1].toLowerCase() : null;
    },
  };
}
