import { connectorDef } from "../catalog.js";
import type { CallContext, Connector, ConnectorDeps, OAuthProvider } from "../connector.js";
import { ProviderError } from "../http.js";
import type { Page, Resource, ResourceDetails } from "../types.js";
import { decodeCursor, encodeCursor, metaOf, num, oauth2Provider, str, ts } from "./oauth2.js";

/**
 * OneDrive (with Excel workbook metadata) and SharePoint through Microsoft Graph v1.0 (docs/INTEGRATIONS.md ›
 * Microsoft). Delegated permissions only: every call runs as the signed-in person and reaches only what Microsoft lets
 * them open. Excel's workbook API works for .xlsx files in OneDrive for Business and SharePoint (not consumer OneDrive,
 * not .xls); Meldiq only lists worksheets and tables, never cell contents, and never edits a workbook.
 *
 * Item ids are "<drive id>~<item id>"; sharing links resolve through /shares/{id}/driveItem, which applies the link's
 * own permissions.
 */
const GRAPH = "https://graph.microsoft.com/v1.0";
const PAGE_SIZE = 25;
const ITEM_SELECT = "id,name,webUrl,size,lastModifiedDateTime,file,folder,parentReference,createdBy,package";
const XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
/** Used ranges are read for at most this many sheets of a workbook. */
const MAX_RANGE_SHEETS = 8;

function microsoftOAuth(deps: ConnectorDeps): OAuthProvider | null {
  const m = deps.config.microsoft;
  if (!m) return null;
  const base = `https://login.microsoftonline.com/${m.tenant}/oauth2/v2.0`;
  return oauth2Provider(
    {
      label: "Microsoft",
      authorizeEndpoint: `${base}/authorize`,
      tokenEndpoint: `${base}/token`,
      clientId: m.clientId,
      clientSecret: m.clientSecret,
      pkce: true,
      clientAuth: "body",
      tokenFormat: "form",
      scopeSeparator: " ",
      scopesInTokenRequest: true,
      authorizeExtras: { response_mode: "query", prompt: "select_account" },
      // Microsoft prefixes Graph permissions with the resource and leaves offline_access out (a refresh token means it
      // was granted).
      normalizeScopes: (granted, hasRefresh) => [...granted.map((s) => s.replace(/^https:\/\/graph\.microsoft\.com\//i, "")), ...(hasRefresh ? ["offline_access"] : [])],
      // Microsoft has no per-app token revocation: disconnecting deletes Meldiq's copy and the person removes the
      // app's consent at myapps.microsoft.com (docs/INTEGRATIONS.md › Disconnecting).
      now: deps.now,
    },
    deps.fetch,
  );
}

interface DriveItem {
  id?: string;
  name?: string;
  webUrl?: string;
  size?: number;
  lastModifiedDateTime?: string;
  file?: { mimeType?: string };
  folder?: { childCount?: number };
  package?: { type?: string };
  parentReference?: { driveId?: string; path?: string; name?: string };
  createdBy?: { user?: { displayName?: string } };
}

const SEG = /^[A-Za-z0-9!._,-]{1,300}$/;
function splitItemId(id: string): { drive: string; item: string } {
  const i = id.indexOf("~");
  const drive = id.slice(0, i);
  const item = id.slice(i + 1);
  if (i <= 0 || !SEG.test(drive) || !SEG.test(item)) throw new ProviderError("not_found", "That isn't a OneDrive or SharePoint file");
  return { drive, item };
}

function kindOf(it: DriveItem): Resource["kind"] {
  if (it.folder || it.package?.type === "oneNote") return "folder";
  const name = (it.name ?? "").toLowerCase();
  if (it.file?.mimeType === XLSX_MIME || name.endsWith(".xlsx")) return "workbook";
  if (name.endsWith(".docx")) return "document";
  if (name.endsWith(".pptx")) return "presentation";
  return "file";
}

function itemResource(it: DriveItem): Resource {
  const drive = it.parentReference?.driveId;
  if (!it.id || !drive) throw new ProviderError("bad_response", "Microsoft Graph sent an item without its drive");
  return {
    id: `${drive}~${it.id}`,
    kind: kindOf(it),
    title: str(it.name) ?? "Untitled",
    url: str(it.webUrl),
    mimeType: str(it.file?.mimeType),
    modifiedAt: ts(it.lastModifiedDateTime),
    owner: str(it.createdBy?.user?.displayName),
    size: num(it.size),
    container: str(it.parentReference?.name),
    browsable: !!it.folder,
  };
}

async function items(ctx: CallContext, url: string, cursor: string | null): Promise<Page<Resource>> {
  const next = decodeCursor(cursor, `${GRAPH}/`);
  const r = await ctx.api<{ value?: DriveItem[]; "@odata.nextLink"?: string }>(next ?? url, next ? {} : { query: { $select: ITEM_SELECT, $top: PAGE_SIZE } });
  return { items: (r?.value ?? []).filter((x) => x.id && x.parentReference?.driveId).map(itemResource), cursor: encodeCursor(r?.["@odata.nextLink"]) };
}

/** Graph's sharing-link id: "u!" + unpadded base64url of the link (documented for /shares). */
export function shareIdFor(url: string): string {
  return `u!${Buffer.from(url, "utf8").toString("base64url")}`;
}

async function getItem(ctx: CallContext, id: string): Promise<Resource> {
  if (id.startsWith("u!")) {
    if (!/^u![A-Za-z0-9_-]{8,3000}$/.test(id)) throw new ProviderError("not_found", "That isn't a OneDrive or SharePoint link");
    return itemResource(await ctx.api<DriveItem>(`${GRAPH}/shares/${id}/driveItem`, { query: { $select: ITEM_SELECT } }));
  }
  const { drive, item } = splitItemId(id);
  return itemResource(await ctx.api<DriveItem>(`${GRAPH}/drives/${drive}/items/${item}`, { query: { $select: ITEM_SELECT } }));
}

function matchMicrosoftUrl(url: URL): string | null {
  if (url.protocol !== "https:") return null;
  const h = url.hostname.toLowerCase();
  if (h.endsWith(".sharepoint.com") || h === "1drv.ms" || h === "onedrive.live.com") {
    url.hash = "";
    return url.toString().length <= 2000 ? shareIdFor(url.toString()) : null;
  }
  return null;
}

/** Worksheets and tables of an .xlsx workbook (names only). */
async function workbookDetails(ctx: CallContext, id: string): Promise<ResourceDetails> {
  const resource = await getItem(ctx, id);
  if (resource.kind !== "workbook") throw new ProviderError("not_found", "Sheets and tables can only be listed for Excel workbooks (.xlsx)");
  const { drive, item } = splitItemId(resource.id);
  const base = `${GRAPH}/drives/${drive}/items/${item}/workbook`;
  try {
    const [sheets, tables] = await Promise.all([
      ctx.api<{ value?: { name?: string; position?: number; visibility?: string }[] }>(`${base}/worksheets`, { query: { $select: "name,position,visibility" } }),
      ctx.api<{ value?: { name?: string; showHeaders?: boolean; showTotals?: boolean }[] }>(`${base}/tables`, { query: { $select: "name,showHeaders,showTotals" } }),
    ]);
    const ordered = (sheets?.value ?? []).sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
    // Each visible sheet's used range: its address and size only (valuesOnly limits it to cells with values; the
    // values themselves aren't requested). At most a few sheets, so a large workbook isn't many calls.
    const sizes = await Promise.all(
      ordered.slice(0, MAX_RANGE_SHEETS).map(async (s) => {
        if (!s.name || (s.visibility && s.visibility !== "Visible")) return null;
        const r = await ctx
          .api<{ address?: string; rowCount?: number; columnCount?: number }>(`${base}/worksheets/${encodeURIComponent(s.name)}/usedRange(valuesOnly=true)`, {
            query: { $select: "address,rowCount,columnCount" },
          })
          .catch(() => null);
        return r?.address ? `${r.address.split("!").pop()} · ${r.rowCount ?? 0} rows × ${r.columnCount ?? 0} columns` : null;
      }),
    );
    return {
      resource,
      sections: [
        {
          title: "Worksheets",
          rows: ordered.map((s, i) => ({
            label: s.name ?? "Sheet",
            value: s.visibility && s.visibility !== "Visible" ? s.visibility : (sizes[i] ?? "Visible"),
          })),
        },
        { title: "Tables", rows: (tables?.value ?? []).map((t) => ({ label: t.name ?? "Table", value: t.showHeaders === false ? "No header row" : "With headers" })) },
      ],
    };
  } catch (e) {
    if (e instanceof ProviderError && (e.kind === "not_found" || e.kind === "bad_response")) {
      throw new ProviderError("not_found", "Excel details are only available for workbooks in OneDrive for Business or SharePoint", e.status);
    }
    throw e;
  }
}

async function msIdentity(ctx: CallContext) {
  const me = await ctx.api<{ id?: string; displayName?: string; userPrincipalName?: string; mail?: string }>(`${GRAPH}/me`, {
    query: { $select: "id,displayName,userPrincipalName,mail" },
  });
  if (!me?.id) throw new ProviderError("bad_response", "Microsoft didn't say whose account this is");
  const label = me.mail ?? me.userPrincipalName ?? me.displayName ?? null;
  return { externalId: me.id, label, meta: metaOf({ email: label }) };
}

/** Graph search text: single quotes doubled inside the OData string. */
const odataString = (q: string) => q.replace(/'/g, "''");

export function microsoftOneDrive(deps: ConnectorDeps): Connector {
  return {
    def: connectorDef("microsoft-onedrive")!,
    oauth: microsoftOAuth(deps),
    identify: (ctx) => msIdentity(ctx),
    search: {
      capability: "files.search",
      async run(ctx, query, cursor) {
        const q = query.trim();
        if (!q) return items(ctx, `${GRAPH}/me/drive/root/children`, cursor);
        return items(ctx, `${GRAPH}/me/drive/root/search(q='${encodeURIComponent(odataString(q))}')`, cursor);
      },
    },
    browse: {
      capability: "files.browse",
      async run(ctx, containerId, cursor) {
        if (!containerId) return items(ctx, `${GRAPH}/me/drive/root/children`, cursor);
        const { drive, item } = splitItemId(containerId);
        return items(ctx, `${GRAPH}/drives/${drive}/items/${item}/children`, cursor);
      },
    },
    get: { capability: "files.link", run: getItem },
    unfurlCapability: "files.unfurl",
    matchUrl: matchMicrosoftUrl,
    details: { capability: "workbook.inspect", run: workbookDetails },
  };
}

interface Site {
  id?: string;
  displayName?: string;
  name?: string;
  webUrl?: string;
  lastModifiedDateTime?: string;
}
const siteResource = (s: Site): Resource => ({
  id: `site~${s.id}`,
  kind: "site",
  title: str(s.displayName) ?? str(s.name) ?? "Site",
  url: str(s.webUrl),
  modifiedAt: ts(s.lastModifiedDateTime),
  browsable: true,
});
const SITE_ID = /^[A-Za-z0-9.,-]{1,400}$/;

export function microsoftSharePoint(deps: ConnectorDeps): Connector {
  return {
    def: connectorDef("microsoft-sharepoint")!,
    oauth: microsoftOAuth(deps),
    identify: (ctx) => msIdentity(ctx),
    search: {
      capability: "sites.search",
      async run(ctx, query, cursor) {
        const next = decodeCursor(cursor, `${GRAPH}/`);
        const r = await ctx.api<{ value?: Site[]; "@odata.nextLink"?: string }>(
          next ?? `${GRAPH}/sites`,
          next ? {} : { query: { search: query.trim() || "*", $select: "id,displayName,name,webUrl,lastModifiedDateTime", $top: PAGE_SIZE } },
        );
        return { items: (r?.value ?? []).filter((s) => s.id).map(siteResource), cursor: encodeCursor(r?.["@odata.nextLink"]) };
      },
    },
    browse: {
      capability: "libraries.browse",
      async run(ctx, containerId, cursor) {
        if (!containerId) {
          // Sites the person follows; search finds the rest.
          const next = decodeCursor(cursor, `${GRAPH}/`);
          const r = await ctx.api<{ value?: Site[]; "@odata.nextLink"?: string }>(next ?? `${GRAPH}/me/followedSites`, next ? {} : { query: { $select: "id,displayName,name,webUrl" } });
          return { items: (r?.value ?? []).filter((s) => s.id).map(siteResource), cursor: encodeCursor(r?.["@odata.nextLink"]) };
        }
        if (containerId.startsWith("site~")) {
          const site = containerId.slice(5);
          if (!SITE_ID.test(site)) throw new ProviderError("not_found", "That isn't a SharePoint site");
          const r = await ctx.api<{ value?: { id?: string; name?: string; webUrl?: string; driveType?: string }[] }>(`${GRAPH}/sites/${site}/drives`, {
            query: { $select: "id,name,webUrl,driveType" },
          });
          return {
            items: (r?.value ?? []).filter((d) => d.id).map((d) => ({ id: `lib~${d.id}`, kind: "library" as const, title: d.name ?? "Documents", url: str(d.webUrl), browsable: true })),
            cursor: null,
          };
        }
        if (containerId.startsWith("lib~")) {
          const drive = containerId.slice(4);
          if (!SEG.test(drive)) throw new ProviderError("not_found", "That isn't a document library");
          return items(ctx, `${GRAPH}/drives/${drive}/root/children`, cursor);
        }
        const { drive, item } = splitItemId(containerId);
        return items(ctx, `${GRAPH}/drives/${drive}/items/${item}/children`, cursor);
      },
    },
    get: { capability: "files.link", run: getItem },
    unfurlCapability: "files.link",
    matchUrl: matchMicrosoftUrl,
    details: { capability: "workbook.inspect", run: workbookDetails },
  };
}

// ───────────── Outlook Calendar ─────────────
interface GraphEvent {
  id?: string;
  subject?: string;
  webLink?: string;
  isCancelled?: boolean;
  start?: { dateTime?: string };
  end?: { dateTime?: string };
  location?: { displayName?: string };
  organizer?: { emailAddress?: { name?: string; address?: string } };
  lastModifiedDateTime?: string;
}

const EVENT_SELECT = "id,subject,webLink,isCancelled,start,end,location,organizer,lastModifiedDateTime";
/** Times come back in UTC (the Prefer header below) without a zone suffix. */
const utc = (v: string | undefined) => (v ? ts(/[zZ]|[+-]\d\d:\d\d$/.test(v) ? v : `${v}Z`) : null);
const UTC_HEADERS = { prefer: 'outlook.timezone="UTC"' };
const DAY_MS = 86_400_000;
const GRAPH_EVENT_ID = /^[A-Za-z0-9=_-]{10,600}$/;

function eventResource(e: GraphEvent): Resource {
  if (!e.id) throw new ProviderError("bad_response", "Outlook sent an event without an id");
  return {
    id: e.id,
    kind: "event",
    title: str(e.subject) ?? "(No title)",
    url: str(e.webLink),
    startAt: utc(e.start?.dateTime),
    endAt: utc(e.end?.dateTime),
    detail: str(e.location?.displayName),
    owner: str(e.organizer?.emailAddress?.name) ?? str(e.organizer?.emailAddress?.address),
    modifiedAt: ts(e.lastModifiedDateTime),
  };
}

/**
 * Outlook Calendar (Calendars.Read, delegated): upcoming events and a search over recent and coming ones, read-only.
 * Searching filters the person's own calendar view (no extra permission, no mail access).
 */
export function microsoftOutlookCalendar(deps: ConnectorDeps): Connector {
  return {
    def: connectorDef("microsoft-outlook-calendar")!,
    oauth: microsoftOAuth(deps),
    identify: (ctx) => msIdentity(ctx),
    search: {
      capability: "events.read",
      async run(ctx, query, cursor) {
        const q = query.trim().toLowerCase();
        const now = deps.now();
        const next = decodeCursor(cursor, `${GRAPH}/`);
        const r = await ctx.api<{ value?: GraphEvent[]; "@odata.nextLink"?: string }>(
          next ?? `${GRAPH}/me/calendarView`,
          next
            ? { headers: UTC_HEADERS }
            : {
                headers: UTC_HEADERS,
                query: {
                  startDateTime: new Date(now - (q ? 90 * DAY_MS : 0)).toISOString(),
                  endDateTime: new Date(now + (q ? 90 : 30) * DAY_MS).toISOString(),
                  $select: EVENT_SELECT,
                  $orderby: "start/dateTime",
                  $top: q ? 100 : PAGE_SIZE,
                },
              },
        );
        const items = (r?.value ?? []).filter((e) => !e.isCancelled && (!q || (e.subject ?? "").toLowerCase().includes(q))).map(eventResource);
        return { items, cursor: q ? null : encodeCursor(r?.["@odata.nextLink"]) };
      },
    },
    get: {
      capability: "events.share",
      async run(ctx, id) {
        if (!GRAPH_EVENT_ID.test(id)) throw new ProviderError("not_found", "That isn't an Outlook event");
        return eventResource(await ctx.api<GraphEvent>(`${GRAPH}/me/events/${id}`, { headers: UTC_HEADERS, query: { $select: EVENT_SELECT } }));
      },
    },
  };
}
