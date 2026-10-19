import { connectorDef } from "../catalog.js";
import type { Connector, ConnectorDeps } from "../connector.js";
import { ProviderError } from "../http.js";
import type { Resource } from "../types.js";
import { metaOf, oauth2Provider, str, ts } from "./oauth2.js";

/**
 * Figma through its REST API with an OAuth token limited to `current_user:read` and `file_metadata:read`
 * (docs/INTEGRATIONS.md › Figma; developers.figma.com/docs/rest-api/oauth-apps and /scopes, checked 2026-10-19):
 * link previews of Figma Design, FigJam, Slides and prototype links with the viewer's own access. Figma's API has no
 * search for a person's files, so nothing is searched; comment notifications (team webhooks) are planned, not built.
 * The file's contents are never read: metadata only.
 */
const API = "https://api.figma.com/v1";
/** File keys as they appear in Figma links. */
const FILE_KEY = /^[A-Za-z0-9]{10,128}$/;
const LINK = /^\/(?:design|file|board|slides|proto|deck)\/([A-Za-z0-9]{10,128})(?:\/|$)/;
const EDITORS: Record<string, string> = { figma: "Figma Design", figjam: "FigJam", slides: "Figma Slides", buzz: "Figma Buzz", sites: "Figma Sites" };

interface FileMeta {
  name?: string;
  folder_name?: string;
  last_touched_at?: string;
  creator?: { handle?: string };
  last_touched_by?: { handle?: string };
  editorType?: string;
  url?: string;
}

function fileResource(key: string, f: FileMeta): Resource {
  if (!f.name) throw new ProviderError("bad_response", "Figma sent a file without a name");
  return {
    id: key,
    kind: "document",
    title: f.name,
    url: str(f.url) ?? `https://www.figma.com/design/${key}`,
    status: EDITORS[f.editorType ?? ""] ?? null,
    container: str(f.folder_name),
    detail: str(f.last_touched_by?.handle) ?? str(f.creator?.handle),
    modifiedAt: ts(f.last_touched_at),
  };
}

export function figmaConnector(deps: ConnectorDeps): Connector {
  const cfg = deps.config.figma;
  return {
    def: connectorDef("figma")!,
    oauth: cfg
      ? oauth2Provider(
          {
            label: "Figma",
            authorizeEndpoint: "https://www.figma.com/oauth",
            tokenEndpoint: `${API}/oauth/token`,
            // Figma refreshes on its own endpoint and keeps the refresh token (it isn't rotated).
            refreshEndpoint: `${API}/oauth/refresh`,
            clientId: cfg.clientId,
            clientSecret: cfg.clientSecret,
            pkce: true,
            clientAuth: "basic",
            tokenFormat: "form",
            scopeSeparator: " ",
            tokenMeta: (raw) => metaOf({ userId: typeof raw.user_id_string === "string" ? raw.user_id_string : null }),
            now: deps.now,
          },
          deps.fetch,
        )
      : null,
    async identify(ctx) {
      const me = await ctx.api<{ id?: string; handle?: string; email?: string }>(`${API}/me`);
      if (!me?.id) throw new ProviderError("bad_response", "Figma didn't say whose account this is");
      return { externalId: me.id, label: me.email ?? me.handle ?? null };
    },
    get: {
      capability: "links.unfurl",
      async run(ctx, id) {
        if (!FILE_KEY.test(id)) throw new ProviderError("not_found", "That isn't a Figma file");
        const r = await ctx.api<{ file?: FileMeta }>(`${API}/files/${id}/meta`);
        if (!r?.file) throw new ProviderError("not_found", "Figma couldn't find that file, or you can't access it");
        return fileResource(id, r.file);
      },
    },
    unfurlCapability: "links.unfurl",
    matchUrl(url) {
      if (url.protocol !== "https:" || (url.hostname !== "www.figma.com" && url.hostname !== "figma.com")) return null;
      return LINK.exec(url.pathname)?.[1] ?? null;
    },
  };
}
