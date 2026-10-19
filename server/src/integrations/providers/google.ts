import { connectorDef } from "../catalog.js";
import type { CallContext, Connector, ConnectorDeps, OAuthProvider } from "../connector.js";
import { ApiError } from "../../util.js";
import { ProviderError, providerJson } from "../http.js";
import type { Page, Resource, ResourceDetails } from "../types.js";
import { metaOf, num, oauth2Provider, quoteEscape, str, ts } from "./oauth2.js";

/**
 * Google Drive (with Docs, Sheets and Slides as Drive files) and Google Calendar (docs/INTEGRATIONS.md › Google).
 * Each is its own connector with its own grant and only its own scopes; Gmail is a separate, planned connector.
 * APIs: Drive v3 files.list/get, Sheets v4 spreadsheets.get (properties only), Calendar v3 events.list/get,
 * OpenID Connect userinfo for the account's stable id.
 */
const DRIVE = "https://www.googleapis.com/drive/v3";
const SHEETS = "https://sheets.googleapis.com/v4/spreadsheets";
const CAL = "https://www.googleapis.com/calendar/v3";
const USERINFO = "https://openidconnect.googleapis.com/v1/userinfo";
const PAGE_SIZE = 25;

const FILE_FIELDS = "id,name,mimeType,modifiedTime,webViewLink,thumbnailLink,owners(displayName),size,driveId";
const MIME = {
  folder: "application/vnd.google-apps.folder",
  document: "application/vnd.google-apps.document",
  spreadsheet: "application/vnd.google-apps.spreadsheet",
  presentation: "application/vnd.google-apps.presentation",
} as const;

function googleOAuth(deps: ConnectorDeps): OAuthProvider | null {
  const g = deps.config.google;
  if (!g) return null;
  return oauth2Provider(
    {
      label: "Google",
      authorizeEndpoint: "https://accounts.google.com/o/oauth2/v2/auth",
      tokenEndpoint: "https://oauth2.googleapis.com/token",
      clientId: g.clientId,
      clientSecret: g.clientSecret,
      pkce: true,
      clientAuth: "body",
      tokenFormat: "form",
      scopeSeparator: " ",
      // A refresh token, a fresh consent showing exactly these scopes, and no scopes from other grants mixed in.
      authorizeExtras: { access_type: "offline", prompt: "consent", include_granted_scopes: "false" },
      // Google answers "email" as …/userinfo.email (and "profile" likewise).
      normalizeScopes: (granted) => granted.map((s) => s.replace(/^https:\/\/www\.googleapis\.com\/auth\/userinfo\.(email|profile)$/, "$1")),
      revoke: async (t) => {
        await providerJson(deps.fetch, "https://oauth2.googleapis.com/revoke", { method: "POST", form: { token: t.refreshToken ?? t.accessToken } }, "Google");
      },
      now: deps.now,
    },
    deps.fetch,
  );
}

async function googleIdentity(ctx: CallContext) {
  const u = await ctx.api<{ sub?: string; email?: string }>(USERINFO);
  if (!u?.sub) throw new ProviderError("bad_response", "Google didn't say whose account this is");
  return { externalId: u.sub, label: u.email ?? null, meta: metaOf({ email: u.email }) };
}

// ───────────── Drive ─────────────
interface DriveFile {
  id?: string;
  name?: string;
  mimeType?: string;
  modifiedTime?: string;
  webViewLink?: string;
  thumbnailLink?: string;
  owners?: { displayName?: string }[];
  size?: string;
  driveId?: string;
}

function kindOf(mime: string | null): Resource["kind"] {
  switch (mime) {
    case MIME.folder:
      return "folder";
    case MIME.document:
      return "document";
    case MIME.spreadsheet:
      return "spreadsheet";
    case MIME.presentation:
      return "presentation";
    default:
      return "file";
  }
}

function driveResource(f: DriveFile): Resource {
  if (!f.id) throw new ProviderError("bad_response", "Google Drive sent a file without an id");
  const mime = str(f.mimeType);
  return {
    id: f.id,
    kind: kindOf(mime),
    title: str(f.name) ?? "Untitled",
    url: str(f.webViewLink),
    mimeType: mime,
    modifiedAt: ts(f.modifiedTime),
    owner: str(f.owners?.[0]?.displayName),
    size: num(f.size),
    browsable: mime === MIME.folder,
    thumbnailUrl: str(f.thumbnailLink),
  };
}

/** Drive file ids: letters, digits, - and _ (Google's own format). Anything else is refused before any call. */
const DRIVE_ID = /^[A-Za-z0-9_-]{10,200}$/;
function driveId(id: string): string {
  if (!DRIVE_ID.test(id)) throw new ProviderError("not_found", "That isn't a Google Drive file");
  return id;
}

const FILTERS: Record<string, string> = {
  documents: `mimeType = '${MIME.document}'`,
  spreadsheets: `mimeType = '${MIME.spreadsheet}'`,
  presentations: `mimeType = '${MIME.presentation}'`,
  folders: `mimeType = '${MIME.folder}'`,
};

async function listFiles(ctx: CallContext, q: string, cursor: string | null, extra: Record<string, string> = {}): Promise<Page<Resource>> {
  const r = await ctx.api<{ files?: DriveFile[]; nextPageToken?: string }>(`${DRIVE}/files`, {
    query: {
      q,
      fields: `nextPageToken,files(${FILE_FIELDS})`,
      pageSize: PAGE_SIZE,
      pageToken: cursor ?? undefined,
      supportsAllDrives: "true",
      includeItemsFromAllDrives: "true",
      ...extra,
    },
  });
  return { items: (r?.files ?? []).map(driveResource), cursor: r?.nextPageToken ?? null };
}

/** Virtual folder listing the person's shared drives. */
const SHARED_DRIVES = "shared-drives";

export function googleDrive(deps: ConnectorDeps): Connector {
  return {
    def: connectorDef("google-drive")!,
    oauth: googleOAuth(deps),
    identify: (ctx) => googleIdentity(ctx),
    search: {
      capability: "files.search",
      async run(ctx, query, cursor, filter) {
        const parts = ["trashed = false"];
        const q = query.trim();
        if (q) parts.push(`name contains '${quoteEscape(q, "'")}'`);
        if (filter && FILTERS[filter]) parts.push(FILTERS[filter]);
        // No words: the person's most recently changed files.
        return listFiles(ctx, parts.join(" and "), cursor, q ? { corpora: "allDrives" } : { orderBy: "modifiedTime desc" });
      },
    },
    browse: {
      capability: "files.browse",
      async run(ctx, containerId, cursor) {
        if (containerId === SHARED_DRIVES) {
          const r = await ctx.api<{ drives?: { id?: string; name?: string }[]; nextPageToken?: string }>(`${DRIVE}/drives`, {
            query: { pageSize: PAGE_SIZE, pageToken: cursor ?? undefined, fields: "nextPageToken,drives(id,name)" },
          });
          return {
            items: (r?.drives ?? []).filter((d) => d.id).map((d) => ({ id: d.id!, kind: "library" as const, title: d.name ?? "Shared drive", url: `https://drive.google.com/drive/folders/${d.id}`, browsable: true })),
            cursor: r?.nextPageToken ?? null,
          };
        }
        const parent = containerId ? driveId(containerId) : "root";
        const page = await listFiles(ctx, `'${parent}' in parents and trashed = false`, cursor, { orderBy: "folder,name" });
        if (containerId || cursor) return page;
        // My Drive's top level, plus a way into shared drives.
        return { items: [{ id: SHARED_DRIVES, kind: "folder", title: "Shared drives", url: null, browsable: true }, ...page.items], cursor: page.cursor };
      },
    },
    get: {
      capability: "files.link",
      async run(ctx, id) {
        return driveResource(await ctx.api<DriveFile>(`${DRIVE}/files/${driveId(id)}`, { query: { fields: FILE_FIELDS, supportsAllDrives: "true" } }));
      },
    },
    unfurlCapability: "files.unfurl",
    matchUrl(url) {
      if (url.protocol !== "https:") return null;
      if (url.hostname === "docs.google.com") {
        const m = /^\/(?:document|spreadsheets|presentation|forms)\/d\/([A-Za-z0-9_-]{10,200})/.exec(url.pathname);
        return m ? m[1] : null;
      }
      if (url.hostname === "drive.google.com") {
        const m = /^\/(?:file\/d|drive\/(?:u\/\d+\/)?folders)\/([A-Za-z0-9_-]{10,200})/.exec(url.pathname);
        if (m) return m[1];
        const id = url.searchParams.get("id");
        return id && DRIVE_ID.test(id) ? id : null;
      }
      return null;
    },
    details: {
      capability: "sheets.inspect",
      async run(ctx, id): Promise<ResourceDetails> {
        const resource = driveResource(await ctx.api<DriveFile>(`${DRIVE}/files/${driveId(id)}`, { query: { fields: FILE_FIELDS, supportsAllDrives: "true" } }));
        if (resource.kind !== "spreadsheet") throw new ProviderError("not_found", "Tabs can only be listed for Google Sheets");
        const s = await ctx.api<{ sheets?: { properties?: { title?: string; index?: number; gridProperties?: { rowCount?: number; columnCount?: number } } }[] }>(
          `${SHEETS}/${driveId(id)}`,
          { query: { fields: "sheets.properties(title,index,gridProperties(rowCount,columnCount))" } },
        );
        const rows = (s?.sheets ?? [])
          .map((x) => x.properties ?? {})
          .sort((a, b) => (a.index ?? 0) - (b.index ?? 0))
          .map((p) => ({ label: p.title ?? "Sheet", value: `${p.gridProperties?.rowCount ?? 0} rows × ${p.gridProperties?.columnCount ?? 0} columns` }));
        return { resource, sections: [{ title: "Tabs", rows }] };
      },
    },
  };
}

// ───────────── Calendar ─────────────
interface CalEvent {
  id?: string;
  summary?: string;
  htmlLink?: string;
  location?: string;
  status?: string;
  start?: { dateTime?: string; date?: string };
  end?: { dateTime?: string; date?: string };
  organizer?: { email?: string; displayName?: string };
  updated?: string;
}

const CAL_ID = /^[A-Za-z0-9._%+@-]{1,200}$/;
const EVENT_ID = /^[A-Za-z0-9_-]{1,200}$/;
/** Event resources are "<calendar>~<event id>" so a link to any of the person's calendars resolves. */
function splitEventId(id: string): { calendar: string; event: string } {
  const i = id.lastIndexOf("~");
  const calendar = i > 0 ? decodeURIComponent(id.slice(0, i)) : "primary";
  const event = i > 0 ? id.slice(i + 1) : id;
  if (!CAL_ID.test(calendar) || !EVENT_ID.test(event)) throw new ProviderError("not_found", "That isn't a Google Calendar event");
  return { calendar, event };
}

function eventResource(e: CalEvent, calendar: string): Resource {
  if (!e.id) throw new ProviderError("bad_response", "Google Calendar sent an event without an id");
  return {
    id: `${encodeURIComponent(calendar)}~${e.id}`,
    kind: "event",
    title: str(e.summary) ?? "(No title)",
    url: str(e.htmlLink),
    startAt: ts(e.start?.dateTime ?? e.start?.date),
    endAt: ts(e.end?.dateTime ?? e.end?.date),
    detail: str(e.location),
    owner: str(e.organizer?.displayName) ?? str(e.organizer?.email),
    status: str(e.status),
    modifiedAt: ts(e.updated),
  };
}

const DAY_MS = 86_400_000;
const MAX_EVENT_MS = 14 * DAY_MS;

export function googleCalendar(deps: ConnectorDeps): Connector {
  return {
    def: connectorDef("google-calendar")!,
    oauth: googleOAuth(deps),
    identify: (ctx) => googleIdentity(ctx),
    search: {
      capability: "events.read",
      async run(ctx, query, cursor) {
        const q = query.trim();
        const r = await ctx.api<{ items?: CalEvent[]; nextPageToken?: string }>(`${CAL}/calendars/primary/events`, {
          query: {
            q: q || undefined,
            // Upcoming events; a search also looks back 90 days.
            timeMin: new Date(deps.now() - (q ? 90 * DAY_MS : 0)).toISOString(),
            singleEvents: "true",
            orderBy: "startTime",
            maxResults: PAGE_SIZE,
            pageToken: cursor ?? undefined,
          },
        });
        return { items: (r?.items ?? []).filter((e) => e.status !== "cancelled").map((e) => eventResource(e, "primary")), cursor: r?.nextPageToken ?? null };
      },
    },
    get: {
      capability: "events.share",
      async run(ctx, id) {
        const { calendar, event } = splitEventId(id);
        return eventResource(await ctx.api<CalEvent>(`${CAL}/calendars/${encodeURIComponent(calendar)}/events/${event}`), calendar);
      },
    },
    unfurlCapability: "events.read",
    actions: {
      // Adds one event to the person's primary calendar, exactly as they confirmed it (never edits or deletes).
      create_event: {
        capability: "events.create",
        label: "Add an event to your calendar",
        async run(ctx, input) {
          const f = input.fields;
          const summary = (f.summary ?? "").trim();
          if (!summary || summary.length > 200) throw new ApiError(400, "bad_request", "Give the event a title (at most 200 characters)");
          const start = Date.parse(f.start ?? "");
          const end = Date.parse(f.end ?? "");
          if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) throw new ApiError(400, "bad_request", "Choose a start and an end after it");
          if (end - start > MAX_EVENT_MS) throw new ApiError(400, "bad_request", "An event can last at most 14 days");
          const description = (f.description ?? "").trim().slice(0, 2000) || undefined;
          const location = (f.location ?? "").trim().slice(0, 200) || undefined;
          const e = await ctx.api<CalEvent>(`${CAL}/calendars/primary/events`, {
            method: "POST",
            json: { summary, description, location, start: { dateTime: new Date(start).toISOString() }, end: { dateTime: new Date(end).toISOString() } },
          });
          return eventResource(e, "primary");
        },
      },
    },
    matchUrl(url) {
      // https://www.google.com/calendar/event?eid=<base64("<event id> <calendar id>")>
      if (url.protocol !== "https:" || !/^(www\.)?google\.com$|^calendar\.google\.com$/.test(url.hostname)) return null;
      const eid = url.searchParams.get("eid");
      if (!eid || !/^[A-Za-z0-9_=-]{8,400}$/.test(eid)) return null;
      const [event, calendar] = Buffer.from(eid, "base64url").toString("utf8").split(" ");
      if (!event || !EVENT_ID.test(event)) return null;
      return `${encodeURIComponent(calendar && CAL_ID.test(calendar) ? calendar : "primary")}~${event}`;
    },
  };
}
