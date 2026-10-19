import { connectorDef } from "../catalog.js";
import type { Connector, ConnectorDeps } from "../connector.js";
import { ProviderError, providerJson } from "../http.js";
import type { Resource } from "../types.js";
import { metaOf, oauth2Provider, str } from "./oauth2.js";

/**
 * Zoom through its REST API with the granular user scopes `user:read:user meeting:read:meeting` (docs/INTEGRATIONS.md
 * › Zoom; developers.zoom.us/docs/integrations/oauth, checked 2026-10-19): previews of Zoom meeting links the viewer
 * can see (their own meetings, or their account's), showing the topic, start time and length. Tokens at
 * zoom.us/oauth/token with HTTP Basic client authentication and PKCE; refresh tokens rotate; revoked at
 * zoom.us/oauth/revoke. The meeting's passcode and join URL from the API are never stored or shown: the preview links
 * to the address that was shared.
 */
const API = "https://api.zoom.us/v2";
/** Zoom meeting ids are 9–11 digits. */
const MEETING_ID = /^\d{9,11}$/;
const JOIN = /^\/(?:j|w|s)\/(\d{9,11})(?:\/|$)/;

interface ZoomMeeting {
  id?: number;
  topic?: string;
  start_time?: string;
  duration?: number;
  timezone?: string;
  status?: string;
  type?: number;
}

function meetingResource(m: ZoomMeeting, sharedUrl: string | null): Resource {
  if (!m.id || !m.topic) throw new ProviderError("bad_response", "Zoom sent a meeting without a topic");
  const start = m.start_time ? Date.parse(m.start_time) : NaN;
  const minutes = typeof m.duration === "number" && m.duration > 0 ? m.duration : null;
  return {
    id: String(m.id),
    kind: "event",
    title: m.topic,
    url: sharedUrl,
    status: m.status === "started" ? "In progress" : minutes ? `${minutes} min` : null,
    detail: str(m.timezone),
    startAt: Number.isFinite(start) ? start : null,
    endAt: Number.isFinite(start) && minutes ? start + minutes * 60_000 : null,
  };
}

export function zoomConnector(deps: ConnectorDeps): Connector {
  const cfg = deps.config.zoom;
  const basic = cfg ? `Basic ${Buffer.from(`${cfg.clientId}:${cfg.clientSecret}`).toString("base64")}` : "";
  return {
    def: connectorDef("zoom")!,
    oauth: cfg
      ? oauth2Provider(
          {
            label: "Zoom",
            authorizeEndpoint: "https://zoom.us/oauth/authorize",
            tokenEndpoint: "https://zoom.us/oauth/token",
            clientId: cfg.clientId,
            clientSecret: cfg.clientSecret,
            pkce: true,
            clientAuth: "basic",
            tokenFormat: "form",
            scopeSeparator: " ",
            revoke: async ({ accessToken }) => {
              await providerJson(deps.fetch, "https://zoom.us/oauth/revoke", { method: "POST", form: { token: accessToken }, headers: { authorization: basic } }, "Zoom");
            },
            now: deps.now,
          },
          deps.fetch,
        )
      : null,
    async identify(ctx) {
      const me = await ctx.api<{ id?: string; email?: string; display_name?: string; account_id?: string }>(`${API}/users/me`);
      if (!me?.id) throw new ProviderError("bad_response", "Zoom didn't say whose account this is");
      return { externalId: me.id, label: me.email ?? me.display_name ?? null, meta: metaOf({ accountId: me.account_id }) };
    },
    get: {
      capability: "links.unfurl",
      async run(ctx, id) {
        if (!MEETING_ID.test(id)) throw new ProviderError("not_found", "That isn't a Zoom meeting");
        const m = await ctx.api<ZoomMeeting>(`${API}/meetings/${id}`);
        if (!m?.id) throw new ProviderError("not_found", "Zoom couldn't find that meeting, or you can't see it");
        return meetingResource(m, `https://zoom.us/j/${id}`);
      },
    },
    unfurlCapability: "links.unfurl",
    matchUrl(url) {
      if (url.protocol !== "https:" || !(url.hostname === "zoom.us" || url.hostname.endsWith(".zoom.us"))) return null;
      return JOIN.exec(url.pathname)?.[1] ?? null;
    },
  };
}
