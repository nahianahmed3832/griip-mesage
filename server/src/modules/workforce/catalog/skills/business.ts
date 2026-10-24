import crypto from "node:crypto";
import { z } from "zod/v4";
import { ApiError } from "../../../../shared/errors.js";
import { untrusted } from "../../prompting.js";
import { defineSkill } from "../../types.js";
import { Brief, column, deliver, finish, MB, mdTable, num, parseCsv, passages, quoteFound, rank, readText, readTexts, round, Summary, TEXT_MIMES, toCsv } from "../common.js";

// ───────────── E-commerce Operations ─────────────

export interface StockLine {
  sku: string;
  name: string;
  qty: number | null;
  price: number | null;
  reorderAt: number;
  status: "out_of_stock" | "low" | "ok" | "unknown";
  value: number | null;
}

/** Stock status per product from a catalogue export: out of stock, below its reorder point (default 10), or fine. */
export function stockReport(csv: string): { lines: StockLine[]; missing: string[] } {
  const { header, rows } = parseCsv(csv);
  const col = {
    sku: column(header, ["sku", "id", "product id", "productid", "variant sku"]),
    name: column(header, ["name", "title", "product", "product name"]),
    qty: column(header, ["qty", "quantity", "stock", "inventory", "inventory quantity", "on hand"]),
    price: column(header, ["price", "unit price", "cost"]),
    reorder: column(header, ["reorder point", "reorderpoint", "min stock", "reorder at"]),
  };
  const missing = Object.entries(col).filter(([k, i]) => i < 0 && k !== "reorder").map(([k]) => k);
  const lines = rows.map((r, i) => {
    const qty = col.qty >= 0 ? num(r[col.qty]) : null;
    const price = col.price >= 0 ? num(r[col.price]) : null;
    const reorderAt = (col.reorder >= 0 ? num(r[col.reorder]) : null) ?? 10;
    const status: StockLine["status"] = qty === null ? "unknown" : qty <= 0 ? "out_of_stock" : qty <= reorderAt ? "low" : "ok";
    return { sku: col.sku >= 0 ? (r[col.sku] ?? "").trim() : `row ${i + 2}`, name: col.name >= 0 ? (r[col.name] ?? "").trim() : "", qty, price, reorderAt, status, value: qty !== null && price !== null ? round(qty * price) : null };
  });
  return { lines, missing };
}

const ShopInput = z.strictObject({ brief: Brief, task: z.enum(["inventory_report", "product_descriptions", "launch_checklist"]).default("inventory_report") });
const ShopOutput = z.strictObject({ task: ShopInput.shape.task, products: z.number().int(), lowStock: z.number().int(), outOfStock: z.number().int(), fileIds: z.array(z.string()), summary: Summary });

export const ecommerceSkill = defineSkill<z.infer<typeof ShopInput>, z.infer<typeof ShopOutput>>({
  id: "ecommerce.ops",
  version: 1,
  input: ShopInput,
  output: ShopOutput,
  tools: ["files.read", "files.write", "channel.post", "website.update"],
  requiredTools: ["files.write"],
  categories: ["reasoning"],
  requiredCategories: ["reasoning"],
  files: { max: 5, maxBytes: 20 * MB, mimes: TEXT_MIMES },
  limits: { maxRuntimeSec: 300, maxModelCalls: 3, maxAttempts: 2, maxOutputFiles: 3 },
  approvals: ["Changing a live store (website.update) needs approval and a store connection; none exists, so it delivers files to import."],
  errors: ["An inventory report needs a product CSV with quantities; rows without a readable quantity are listed as unknown."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const csvFile = ctx.inputs.find((f) => f.mime === "text/csv");
    const csv = csvFile ? (await readText(ctx, csvFile, 10_000_000)).text : null;
    if (input.task === "inventory_report") {
      if (!csv) throw new ApiError(400, "bad_request", "Send the product or inventory export as a CSV.");
      const { lines, missing } = stockReport(csv);
      if (!lines.length || missing.includes("qty")) throw new ApiError(400, "bad_request", `The CSV needs a quantity column (and ideally sku, name, price). Missing: ${missing.join(", ")}.`);
      const low = lines.filter((l) => l.status === "low");
      const out = lines.filter((l) => l.status === "out_of_stock");
      const value = round(lines.reduce((n, l) => n + (l.value ?? 0), 0));
      const header = ["SKU", "Name", "Qty", "Reorder at", "Status", "Price", "Stock value"];
      const rows = [...out, ...low, ...lines.filter((l) => l.status !== "low" && l.status !== "out_of_stock")].map((l) => [l.sku, l.name, l.qty, l.reorderAt, l.status, l.price, l.value]);
      const narrative = await ctx.models.text("reasoning", {
        system: "Write a short inventory briefing with concrete reorder priorities from these computed figures only.",
        prompt: `${untrusted("brief", input.brief)}\n\n${untrusted("inventory", `${lines.length} products; ${out.length} out of stock; ${low.length} low; stock value ${value}.\nOut of stock: ${out.slice(0, 40).map((l) => `${l.sku} ${l.name}`).join("; ")}\nLow: ${low.slice(0, 40).map((l) => `${l.sku} ${l.name} (${l.qty}/${l.reorderAt})`).join("; ")}`)}`,
        maxOutputTokens: 1200,
      });
      const md = await deliver(ctx, "inventory-report.md", "text/markdown", `# Inventory report\n\n${lines.length} products · ${out.length} out of stock · ${low.length} low · stock value ${value}\n\n${narrative}\n\n${mdTable(header, rows.slice(0, 200))}\n`);
      const c = await deliver(ctx, "inventory-status.csv", "text/csv", toCsv(header, rows));
      const summary = `${lines.length} products: ${out.length} out of stock, ${low.length} low.`;
      return finish(ctx, { task: input.task, products: lines.length, lowStock: low.length, outOfStock: out.length, fileIds: [md.id, c.id], summary }, summary, [md.id, c.id]);
    }
    if (input.task === "product_descriptions") {
      const products = csv ? parseCsv(csv) : null;
      const list = products ? products.rows.slice(0, 25).map((r) => Object.fromEntries(products.header.map((h, i) => [h, r[i] ?? ""]))) : [];
      const Desc = z.object({ products: z.array(z.object({ ref: z.string().max(100), title: z.string().max(150), description: z.string().max(2000), bullets: z.array(z.string().max(200)).max(6).default([]) })).min(1).max(25) });
      const d = await ctx.models.json("reasoning", Desc, {
        system: 'Write product titles, descriptions and bullet points from the data only; never invent specifications. "ref" is the product\'s SKU or row. JSON only: {"products":[{"ref","title","description","bullets"}]}.',
        prompt: `${untrusted("brief", input.brief)}\n\n${untrusted("products", JSON.stringify(list).slice(0, 60_000) || "(no product file; use the brief)")}`,
        maxOutputTokens: 6000,
      });
      const header = ["Ref", "Title", "Description", "Bullets"];
      const rows = d.products.map((p) => [p.ref, p.title, p.description, p.bullets.join(" | ")]);
      const c = await deliver(ctx, "product-descriptions.csv", "text/csv", toCsv(header, rows));
      const summary = `${d.products.length} product descriptions, ready to import.`;
      return finish(ctx, { task: input.task, products: d.products.length, lowStock: 0, outOfStock: 0, fileIds: [c.id], summary }, summary, [c.id]);
    }
    const sources = await readTexts(ctx, TEXT_MIMES, 40_000);
    const List = z.object({ items: z.array(z.object({ phase: z.string().max(60), task: z.string().max(300), owner: z.string().max(80).default("") })).min(1).max(60) });
    const l = await ctx.models.json("reasoning", List, { system: 'Write a product launch checklist by phase. JSON only: {"items":[{"phase","task","owner"}]}.', prompt: `${untrusted("brief", input.brief)}\n\n${sources.map((s) => untrusted(s.name, s.text)).join("\n\n")}`, maxOutputTokens: 3000 });
    const c = await deliver(ctx, "launch-checklist.csv", "text/csv", toCsv(["Phase", "Task", "Owner"], l.items.map((i) => [i.phase, i.task, i.owner])));
    const summary = `Launch checklist with ${l.items.length} tasks.`;
    return finish(ctx, { task: input.task, products: 0, lowStock: 0, outOfStock: 0, fileIds: [c.id], summary }, summary, [c.id]);
  },
});

// ───────────── Customer Support ─────────────

const Triage = z.object({
  category: z.string().max(60),
  priority: z.enum(["urgent", "high", "normal", "low"]),
  sentiment: z.enum(["negative", "neutral", "positive"]),
  reply: z.string().max(4000),
  citations: z.array(z.object({ source: z.string(), quote: z.string().max(400) })).max(6).default([]),
  escalate: z.boolean(),
  escalationReason: z.string().max(500).nullable().default(null),
});
const SupportOutput = z.strictObject({ category: z.string(), priority: z.string(), escalate: z.boolean(), verifiedCitations: z.number().int(), sent: z.literal(false), draftFileId: z.string(), summary: Summary });

export const supportSkill = defineSkill<{ brief: string }, z.infer<typeof SupportOutput>>({
  id: "support.assist",
  version: 1,
  input: z.strictObject({ brief: Brief }),
  output: SupportOutput,
  tools: ["files.read", "files.write", "channel.post", "support.reply_external"],
  requiredTools: ["files.write"],
  categories: ["reasoning", "fast"],
  requiredCategories: ["reasoning"],
  files: { max: 20, maxBytes: 10 * MB, mimes: TEXT_MIMES },
  limits: { maxRuntimeSec: 180, maxModelCalls: 2, maxAttempts: 2, maxOutputFiles: 1 },
  approvals: ["Sending a reply to a customer (support.reply_external) needs approval and a help desk connection; none exists, so it drafts."],
  errors: ["Without knowledge-base files the draft says it couldn't check the answer, and is marked for escalation.", "A citation not found in the knowledge base is dropped."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const kb = await readTexts(ctx);
    const relevant = rank(input.brief, passages(kb), 12);
    const t = await ctx.models.json("reasoning", Triage, {
      system: 'You\'re a support agent. Classify the customer message and draft a reply using only the knowledge base; cite it with exact quotes ({"source": the id before "#", "quote"}). Escalate when the knowledge base doesn\'t answer it, or for refunds, legal or safety issues. JSON only: {"category","priority","sentiment","reply","citations","escalate","escalationReason"}.',
      prompt: `Customer message:\n${untrusted("customer", input.brief)}\n\nKnowledge base:\n${relevant.map((p) => untrusted(`${p.sourceId}#${p.index} (${p.sourceName})`, p.text)).join("\n\n") || "(none sent)"}`,
      maxOutputTokens: 2000,
    });
    const byId = new Map(kb.map((s) => [s.id, s]));
    const verified = t.citations.filter((c) => {
      const s = byId.get(c.source.split("#")[0]!);
      return s !== undefined && quoteFound(c.quote, s.text);
    });
    const escalate = t.escalate || kb.length === 0 || (t.citations.length > 0 && verified.length === 0);
    const md = `# Support draft\n\n**Category:** ${t.category} · **Priority:** ${t.priority} · **Sentiment:** ${t.sentiment}${escalate ? `\n\n**Escalate:** ${t.escalationReason ?? (kb.length ? "the knowledge base doesn't confirm the answer" : "no knowledge base was sent to check the answer against")}` : ""}\n\n## Draft reply\n\n${t.reply}\n\n## Sources\n\n${verified.map((c) => `- ${byId.get(c.source.split("#")[0]!)!.name}: “${c.quote}”`).join("\n") || "None verified."}\n\nNot sent: no help desk is connected.\n`;
    const f = await deliver(ctx, "support-draft.md", "text/markdown", md);
    const summary = `${t.priority} ${t.category} message; draft reply ready${escalate ? ", marked for escalation" : ""}.`;
    return finish(ctx, { category: t.category, priority: t.priority, escalate, verifiedCitations: verified.length, sent: false, draftFileId: f.id, summary }, summary, [f.id]);
  },
});

// ───────────── Sales and CRM ─────────────

const SalesInput = z.strictObject({ brief: Brief, task: z.enum(["outreach", "activity_summary"]).default("outreach"), drafts: z.number().int().min(1).max(10).default(3) });
const SalesOutput = z.strictObject({ task: SalesInput.shape.task, items: z.number().int(), fileIds: z.array(z.string()), summary: Summary });

export const salesSkill = defineSkill<z.infer<typeof SalesInput>, z.infer<typeof SalesOutput>>({
  id: "sales.crm",
  version: 1,
  input: SalesInput,
  output: SalesOutput,
  tools: ["files.read", "files.write", "channel.post", "crm.write", "email.send"],
  requiredTools: ["files.write"],
  categories: ["reasoning"],
  requiredCategories: ["reasoning"],
  files: { max: 5, maxBytes: 20 * MB, mimes: TEXT_MIMES },
  limits: { maxRuntimeSec: 180, maxModelCalls: 2, maxAttempts: 2, maxOutputFiles: 2 },
  approvals: ["Updating a CRM or sending email needs approval and a connection; none exists, so it drafts and summarises."],
  errors: ["An activity summary needs a CSV export of deals or activities with a stage or status column."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    if (input.task === "activity_summary") {
      const f = ctx.inputs.find((x) => x.mime === "text/csv");
      if (!f) throw new ApiError(400, "bad_request", "Send the CRM export (deals or activities) as a CSV.");
      const { header, rows } = parseCsv((await readText(ctx, f, 10_000_000)).text);
      const stage = column(header, ["stage", "status", "deal stage", "dealstage", "pipeline stage"]);
      const owner = column(header, ["owner", "rep", "assigned to", "salesperson"]);
      const amount = column(header, ["amount", "value", "deal value", "revenue"]);
      if (stage < 0) throw new ApiError(400, "bad_request", "The CSV needs a stage or status column.");
      const group = (i: number) => {
        const m = new Map<string, { n: number; amount: number }>();
        for (const r of rows) {
          const k = (r[i] ?? "").trim() || "(blank)";
          const cur = m.get(k) ?? { n: 0, amount: 0 };
          m.set(k, { n: cur.n + 1, amount: cur.amount + (amount >= 0 ? (num(r[amount]) ?? 0) : 0) });
        }
        return [...m.entries()].sort((a, b) => b[1].n - a[1].n);
      };
      const byStage = group(stage);
      const byOwner = owner >= 0 ? group(owner) : [];
      const narrative = await ctx.models.text("reasoning", { system: "Summarise the pipeline for a sales lead from these computed figures only: what's moving, what's stuck, what to do next.", prompt: `${untrusted("brief", input.brief)}\n\n${untrusted("pipeline", `By stage: ${byStage.map(([k, v]) => `${k} ${v.n} (${round(v.amount)})`).join("; ")}\nBy owner: ${byOwner.map(([k, v]) => `${k} ${v.n} (${round(v.amount)})`).join("; ")}`)}`, maxOutputTokens: 1000 });
      const md = await deliver(ctx, "pipeline-summary.md", "text/markdown", `# Pipeline summary\n\n${rows.length} records.\n\n${narrative}\n\n## By stage\n\n${mdTable(["Stage", "Count", "Amount"], byStage.map(([k, v]) => [k, v.n, round(v.amount)]))}\n${byOwner.length ? `\n## By owner\n\n${mdTable(["Owner", "Count", "Amount"], byOwner.map(([k, v]) => [k, v.n, round(v.amount)]))}\n` : ""}`);
      const summary = `${rows.length} records in ${byStage.length} stages summarised.`;
      return finish(ctx, { task: input.task, items: rows.length, fileIds: [md.id], summary }, summary, [md.id]);
    }
    const sources = await readTexts(ctx, TEXT_MIMES, 40_000);
    const Drafts = z.object({ drafts: z.array(z.object({ audience: z.string().max(120), subject: z.string().min(1).max(80), body: z.string().min(1).max(3000), followUp: z.string().max(500).default("") })).min(1).max(10) });
    const d = await ctx.models.json("reasoning", Drafts, { system: `Write ${input.drafts} personalised outreach emails (subject up to 80 characters, body under 180 words, one clear call to action). Use only facts given; no fake familiarity. JSON only: {"drafts":[{"audience","subject","body","followUp"}]}.`, prompt: `${untrusted("brief", input.brief)}\n\n${sources.map((s) => untrusted(s.name, s.text)).join("\n\n")}`, maxOutputTokens: 4000 });
    const md = await deliver(ctx, "outreach-drafts.md", "text/markdown", `# Outreach drafts\n\n${d.drafts.map((x, i) => `## ${i + 1}. ${x.audience}\n\n**Subject:** ${x.subject}\n\n${x.body}${x.followUp ? `\n\n*Follow-up:* ${x.followUp}` : ""}`).join("\n\n")}\n\nNot sent and not logged in a CRM: no connection exists.\n`);
    const summary = `${d.drafts.length} outreach drafts.`;
    return finish(ctx, { task: input.task, items: d.drafts.length, fileIds: [md.id], summary }, summary, [md.id]);
  },
});

// ───────────── Email and Calendar ─────────────

const icsText = (s: string) => s.replace(/\\/g, "\\\\").replace(/;/g, "\\;").replace(/,/g, "\\,").replace(/\r?\n/g, "\\n");
const icsTime = (d: Date) => d.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");

/** An RFC 5545 event. The start must be a real date-time; the duration is 5 minutes to 8 hours. */
export function icsEvent(e: { title: string; start: string; durationMinutes: number; location: string; description: string; attendees: string[] }, stamp: Date): string {
  const start = new Date(e.start);
  if (Number.isNaN(start.getTime())) throw new ApiError(400, "bad_request", "The meeting's start time isn't a valid date and time.");
  if (e.durationMinutes < 5 || e.durationMinutes > 480) throw new ApiError(400, "bad_request", "A meeting is 5 minutes to 8 hours.");
  const end = new Date(start.getTime() + e.durationMinutes * 60_000);
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Meldiq//AI Workforce//EN",
    "METHOD:PUBLISH",
    "BEGIN:VEVENT",
    `UID:${crypto.randomUUID()}@meldiq.app`,
    `DTSTAMP:${icsTime(stamp)}`,
    `DTSTART:${icsTime(start)}`,
    `DTEND:${icsTime(end)}`,
    `SUMMARY:${icsText(e.title)}`,
    ...(e.location ? [`LOCATION:${icsText(e.location)}`] : []),
    ...(e.description ? [`DESCRIPTION:${icsText(e.description)}`] : []),
    ...e.attendees.filter((a) => /^[^\s@,;:]+@[^\s@,;:]+\.[^\s@,;:]+$/.test(a)).map((a) => `ATTENDEE;RSVP=TRUE:mailto:${a}`),
    "END:VEVENT",
    "END:VCALENDAR",
  ];
  return `${lines.join("\r\n")}\r\n`;
}

const MailInput = z.strictObject({ brief: Brief, task: z.enum(["draft_email", "schedule_meeting", "summarize"]).default("draft_email") });
const MailOutput = z.strictObject({ task: MailInput.shape.task, fileIds: z.array(z.string()), sent: z.literal(false), summary: Summary });

export const emailSkill = defineSkill<z.infer<typeof MailInput>, z.infer<typeof MailOutput>>({
  id: "email.calendar",
  version: 1,
  input: MailInput,
  output: MailOutput,
  tools: ["files.read", "files.write", "channel.post", "email.send", "calendar.write"],
  requiredTools: ["files.write"],
  categories: ["reasoning", "fast"],
  requiredCategories: ["reasoning"],
  files: { max: 10, maxBytes: 10 * MB, mimes: TEXT_MIMES },
  limits: { maxRuntimeSec: 180, maxModelCalls: 2, maxAttempts: 2, maxOutputFiles: 2 },
  approvals: ["Sending email or changing a calendar needs approval and a connection; none exists, so it delivers a draft or an .ics file to add by hand."],
  errors: ["A meeting with an invalid start or a duration outside 5 minutes to 8 hours is refused."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const sources = await readTexts(ctx, TEXT_MIMES, 60_000);
    const material = `${untrusted("request", input.brief)}${sources.length ? `\n\n${sources.map((s) => untrusted(s.name, s.text)).join("\n\n")}` : ""}`;
    if (input.task === "schedule_meeting") {
      const Ev = z.object({ title: z.string().min(1).max(150), start: z.string().max(40), durationMinutes: z.number().int(), location: z.string().max(200).default(""), description: z.string().max(2000).default(""), attendees: z.array(z.string().max(200)).max(50).default([]) });
      const e = await ctx.models.json("reasoning", Ev, { system: 'Extract one meeting. "start" is an ISO 8601 date-time with its UTC offset (e.g. 2026-11-02T15:00:00+01:00); attendees are email addresses only if given. JSON only: {"title","start","durationMinutes","location","description","attendees"}.', prompt: material, maxOutputTokens: 800 });
      const f = await deliver(ctx, "meeting.ics", "text/calendar", icsEvent(e, new Date()));
      const summary = `Calendar file for "${e.title}" on ${new Date(e.start).toISOString().slice(0, 16).replace("T", " ")} UTC, ${e.durationMinutes} min. Not added to any calendar: none is connected.`;
      return finish(ctx, { task: input.task, fileIds: [f.id], sent: false, summary }, summary, [f.id]);
    }
    if (input.task === "summarize") {
      const S = z.object({ summary: z.string().max(3000), actions: z.array(z.string().max(300)).max(30).default([]), replyNeeded: z.boolean() });
      const s = await ctx.models.json("reasoning", S, { system: 'Summarise these emails: what matters, what\'s asked of the reader. JSON only: {"summary","actions","replyNeeded"}.', prompt: material, maxOutputTokens: 1500 });
      const f = await deliver(ctx, "email-summary.md", "text/markdown", `# Email summary\n\n${s.summary}\n\n${s.actions.length ? `## Actions\n\n${s.actions.map((a) => `- ${a}`).join("\n")}\n` : ""}${s.replyNeeded ? "\nA reply is needed.\n" : ""}`);
      return finish(ctx, { task: input.task, fileIds: [f.id], sent: false, summary: s.summary.slice(0, 280) }, s.summary.slice(0, 280), [f.id]);
    }
    const D = z.object({ to: z.string().max(200).default(""), subject: z.string().min(1).max(150), body: z.string().min(1).max(6000) });
    const m = await ctx.models.json("reasoning", D, { system: 'Draft the email. JSON only: {"to","subject","body"}.', prompt: material, maxOutputTokens: 2000 });
    const f = await deliver(ctx, "email-draft.md", "text/markdown", `**To:** ${m.to || "(fill in)"}\n**Subject:** ${m.subject}\n\n${m.body}\n\n---\nNot sent: no email account is connected.\n`);
    const summary = `Email draft "${m.subject}".`;
    return finish(ctx, { task: input.task, fileIds: [f.id], sent: false, summary }, summary, [f.id]);
  },
});
