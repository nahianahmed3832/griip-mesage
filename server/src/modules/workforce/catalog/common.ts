import { z } from "zod/v4";
import { ApiError } from "../../../shared/errors.js";
import { untrusted } from "../prompting.js";
import { transcript } from "../sharedMessages.js";
import type { FileRef, SkillContext, SkillOutcome } from "../types.js";

/**
 * What skills share: reading submitted text, CSV in and out, picking relevant passages, checking that quoted text
 * really is in its source, and delivering files and posts. Deterministic code: the parts a model must not be trusted
 * with (numbers, quotes, structure) are computed or checked here.
 */
export const TEXT_MIMES = ["text/plain", "text/markdown", "text/csv", "text/html", "application/json"] as const;
export const AUDIO_MIMES = ["audio/mpeg", "audio/wav", "audio/x-wav", "audio/mp4", "audio/ogg", "audio/webm"] as const;
export const VIDEO_MIMES = ["video/mp4", "video/quicktime", "video/webm"] as const;
export const IMAGE_MIMES = ["image/png", "image/jpeg", "image/webp", "image/gif"] as const;

export const Brief = z.string().trim().min(1).max(8000);
export const MB = 1024 * 1024;

/** Characters of submitted material one prompt may carry. */
export const SOURCE_CHARS = 120_000;

export interface Source {
  id: string;
  name: string;
  text: string;
}

export async function readText(ctx: SkillContext, f: FileRef, maxChars = SOURCE_CHARS): Promise<Source> {
  const r = await ctx.tool("files.read", { fileId: f.id });
  let text = r.bytes.toString("utf8");
  if (f.mime === "text/html") text = htmlToText(text);
  return { id: f.id, name: f.name, text: text.slice(0, maxChars) };
}

/** The channel messages shared with the request, as a source (null when none were shared). */
export function sharedSource(ctx: SkillContext, maxChars = SOURCE_CHARS): Source | null {
  if (!ctx.sharedMessages?.length) return null;
  return { id: SHARED_SOURCE_ID, name: "channel-messages.txt", text: transcript(ctx.sharedMessages).slice(-maxChars) };
}
export const SHARED_SOURCE_ID = "channel";

/** Submitted text files of [mimes], within [maxChars] in all; shared channel messages come first when text is wanted. */
export async function readTexts(ctx: SkillContext, mimes: readonly string[] = TEXT_MIMES, maxChars = SOURCE_CHARS): Promise<Source[]> {
  const out: Source[] = [];
  let budget = maxChars;
  const shared = mimes.includes("text/plain") ? sharedSource(ctx, budget) : null;
  if (shared) {
    out.push(shared);
    budget -= shared.text.length;
  }
  for (const f of ctx.inputs.filter((i) => mimes.includes(i.mime))) {
    if (budget <= 0) break;
    const s = await readText(ctx, f, budget);
    budget -= s.text.length;
    out.push(s);
  }
  return out;
}

export function htmlToText(html: string): string {
  return html
    .replace(/<(script|style|noscript)[\s\S]*?<\/\1>/gi, " ")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|li|h[1-6]|tr)>/gi, "\n")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/[ \t]+/g, " ")
    .replace(/\n\s*\n+/g, "\n\n")
    .trim();
}

export function sourcesBlock(sources: Source[]): string {
  return sources.map((s) => untrusted(`${s.id} (${s.name})`, s.text)).join("\n\n");
}

// ───────────── CSV ─────────────

/** RFC 4180 CSV: quoted fields, doubled quotes, CRLF or LF. */
export function parseCsv(text: string, maxRows = 50_000): { header: string[]; rows: string[][] } {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  const endRow = () => {
    row.push(field);
    field = "";
    if (row.some((x) => x !== "")) rows.push(row);
    row = [];
  };
  for (let i = 0; i < text.length && rows.length <= maxRows; i++) {
    const c = text[i]!;
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        field += '"';
        i++;
      } else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"' && field === "") quoted = true;
    else if (c === ",") {
      row.push(field);
      field = "";
    } else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      endRow();
    } else field += c;
  }
  if (field !== "" || row.length) endRow();
  const [header = [], ...body] = rows;
  return { header: header.map((h) => h.trim()), rows: body };
}

export function toCsv(header: string[], rows: (string | number | null)[][]): string {
  const cell = (v: string | number | null) => {
    const s = v === null ? "" : String(v);
    // A leading = + - @ would run as a formula in spreadsheets: neutralise it.
    const safe = /^[=+\-@\t\r]/.test(s) && !/^-?\d+(\.\d+)?$/.test(s) ? `'${s}` : s;
    return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
  };
  return [header, ...rows].map((r) => r.map(cell).join(",")).join("\n") + "\n";
}

/** The column whose name matches one of [names] (case and punctuation ignored), or -1. */
export function column(header: string[], names: string[]): number {
  const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, "");
  const want = names.map(norm);
  return header.findIndex((h) => want.includes(norm(h)));
}

export function num(v: string | undefined): number | null {
  if (v === undefined) return null;
  const s = v.replace(/[$€£,\s]/g, "").replace(/^\((.*)\)$/, "-$1");
  if (s === "" || !/^-?\d*\.?\d+(e-?\d+)?%?$/i.test(s)) return null;
  const n = Number(s.replace("%", ""));
  return Number.isFinite(n) ? n : null;
}

export const round = (n: number, d = 2) => Math.round(n * 10 ** d) / 10 ** d;

export function median(xs: number[]): number | null {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2;
}

export function mdTable(header: string[], rows: (string | number | null)[][]): string {
  const esc = (v: string | number | null) => String(v ?? "").replace(/\|/g, "\\|").replace(/\n/g, " ");
  return [`| ${header.map(esc).join(" | ")} |`, `| ${header.map(() => "---").join(" | ")} |`, ...rows.map((r) => `| ${r.map(esc).join(" | ")} |`)].join("\n");
}

// ───────────── Retrieval and quotes ─────────────

export interface Passage {
  sourceId: string;
  sourceName: string;
  index: number;
  text: string;
}

export function passages(sources: Source[], size = 900): Passage[] {
  const out: Passage[] = [];
  for (const s of sources) {
    const paras = s.text.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
    let buf = "";
    let i = 0;
    for (const p of paras) {
      if (buf && buf.length + p.length > size) {
        out.push({ sourceId: s.id, sourceName: s.name, index: i++, text: buf });
        buf = "";
      }
      buf = buf ? `${buf}\n\n${p}` : p;
    }
    if (buf) out.push({ sourceId: s.id, sourceName: s.name, index: i, text: buf });
  }
  return out;
}

const tokens = (s: string): string[] => s.toLowerCase().match(/[\p{L}\p{N}]{3,}/gu) ?? [];

/** The [k] passages that best match [query] (BM25). */
export function rank(query: string, ps: Passage[], k: number): Passage[] {
  const q = [...new Set(tokens(query))];
  if (!q.length || !ps.length) return ps.slice(0, k);
  const docs = ps.map((p) => tokens(p.text));
  const avg = docs.reduce((n, d) => n + d.length, 0) / docs.length;
  const df = new Map(q.map((t) => [t, docs.filter((d) => d.includes(t)).length]));
  const scored = ps.map((p, i) => {
    const d = docs[i]!;
    let score = 0;
    for (const t of q) {
      const f = d.filter((x) => x === t).length;
      if (!f) continue;
      const idf = Math.log(1 + (ps.length - df.get(t)! + 0.5) / (df.get(t)! + 0.5));
      score += (idf * f * 2.2) / (f + 1.2 * (0.25 + 0.75 * (d.length / avg)));
    }
    return { p, score };
  });
  return scored.sort((a, b) => b.score - a.score).slice(0, k).map((x) => x.p);
}

const squash = (s: string) => s.toLowerCase().replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/\s+/g, " ").trim();

/** Whether [quote] appears in [source] (whitespace, case and curly quotes ignored), at least 12 characters. */
export function quoteFound(quote: string, source: string): boolean {
  const q = squash(quote);
  return q.length >= 12 && squash(source).includes(q);
}

// ───────────── Delivering ─────────────

export async function deliver(ctx: SkillContext, name: string, mime: "text/markdown" | "text/csv" | "application/json" | "text/calendar" | "text/x-diff" | "application/x-subrip" | "text/plain", text: string, kind = "document") {
  return ctx.tool("files.write", { name, mime, bytes: Buffer.from(text, "utf8"), kind });
}

/** Posts in the request's channel when the agent may (a job from the AI panel has no channel: nothing happens). */
export async function post(ctx: SkillContext, kind: "ack" | "progress" | "result" | "approval" | "error", body: string, fileIds: string[] = [], approvalId: string | null = null): Promise<void> {
  if (!ctx.job.conversationId) return;
  try {
    await ctx.tool("channel.post", { kind, body, fileIds, approvalId });
  } catch (e) {
    // Posting may not be granted to this agent: its result is still in the job.
    if (!(e instanceof ApiError && (e.code === "tool_not_allowed" || e.code === "agent_not_assigned"))) throw e;
  }
}

/** Posts the result and returns the job's outcome. */
export async function finish<O>(ctx: SkillContext, output: O, summary: string, fileIds: string[], message?: string): Promise<SkillOutcome<O>> {
  await post(ctx, "result", message ?? summary, fileIds);
  return { kind: "done", output, summary, fileIds };
}

export function requireInputs(ctx: SkillContext, mimes: readonly string[], what: string): FileRef[] {
  const files = ctx.inputs.filter((f) => mimes.includes(f.mime));
  if (!files.length) throw new ApiError(400, "bad_request", `Send ${what} with the request.`);
  return files;
}

/** Common output fields every skill returns. */
export const Summary = z.string().max(2000);
