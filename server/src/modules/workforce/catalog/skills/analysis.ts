import { z } from "zod/v4";
import { ApiError } from "../../../../shared/errors.js";
import { untrusted } from "../../prompting.js";
import { defineSkill } from "../../types.js";
import { AUDIO_MIMES, Brief, column, deliver, finish, MB, mdTable, median, num, parseCsv, readText, readTexts, requireInputs, round, Summary, TEXT_MIMES, toCsv, VIDEO_MIMES } from "../common.js";

// ───────────── Data and Analytics ─────────────

export interface ColumnProfile {
  name: string;
  type: "number" | "date" | "text";
  filled: number;
  missing: number;
  unique: number;
  min: number | null;
  max: number | null;
  mean: number | null;
  median: number | null;
  stdev: number | null;
  top: { value: string; count: number }[];
}

/** Each column's type and statistics. A column is numeric when at least 90% of its filled cells are numbers. */
export function profile(header: string[], rows: string[][]): ColumnProfile[] {
  return header.map((name, c) => {
    const cells = rows.map((r) => (r[c] ?? "").trim());
    const filled = cells.filter((v) => v !== "");
    const nums = filled.map((v) => num(v)).filter((n): n is number => n !== null);
    const dates = filled.filter((v) => /^\d{4}-\d{2}-\d{2}/.test(v) && !Number.isNaN(Date.parse(v)));
    const type = filled.length && nums.length >= 0.9 * filled.length ? "number" : filled.length && dates.length >= 0.9 * filled.length ? "date" : "text";
    const counts = new Map<string, number>();
    for (const v of filled) counts.set(v, (counts.get(v) ?? 0) + 1);
    const mean = type === "number" && nums.length ? nums.reduce((a, b) => a + b, 0) / nums.length : null;
    const stdev = mean !== null && nums.length > 1 ? Math.sqrt(nums.reduce((s, x) => s + (x - mean) ** 2, 0) / (nums.length - 1)) : null;
    return {
      name,
      type,
      filled: filled.length,
      missing: cells.length - filled.length,
      unique: counts.size,
      min: type === "number" && nums.length ? Math.min(...nums) : null,
      max: type === "number" && nums.length ? Math.max(...nums) : null,
      mean: mean === null ? null : round(mean, 4),
      median: type === "number" ? median(nums) : null,
      stdev: stdev === null ? null : round(stdev, 4),
      top: type === "number" ? [] : [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5).map(([value, count]) => ({ value, count })),
    };
  });
}

export function aggregate(header: string[], rows: string[][], groupBy: string, metric: string | null, how: "sum" | "mean" | "count" | "median"): { group: string; value: number; n: number }[] {
  const g = header.indexOf(groupBy);
  const m = metric ? header.indexOf(metric) : -1;
  const by = new Map<string, number[]>();
  for (const r of rows) {
    const k = (r[g] ?? "").trim() || "(blank)";
    const v = m >= 0 ? num(r[m]) : 1;
    if (v === null) continue;
    by.set(k, [...(by.get(k) ?? []), v]);
  }
  return [...by.entries()]
    .map(([group, xs]) => ({ group, n: xs.length, value: round(how === "count" ? xs.length : how === "sum" ? xs.reduce((a, b) => a + b, 0) : how === "mean" ? xs.reduce((a, b) => a + b, 0) / xs.length : median(xs)!, 4) }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 500);
}

const DataOutput = z.strictObject({ rows: z.number().int(), columns: z.number().int(), aggregation: z.string().nullable(), fileIds: z.array(z.string()), summary: Summary });

export const dataSkill = defineSkill<{ brief: string }, z.infer<typeof DataOutput>>({
  id: "data.analyze",
  version: 1,
  input: z.strictObject({ brief: Brief }),
  output: DataOutput,
  tools: ["files.read", "files.write", "channel.post"],
  requiredTools: ["files.read", "files.write"],
  categories: ["reasoning"],
  requiredCategories: ["reasoning"],
  files: { min: 1, max: 3, maxBytes: 50 * MB, mimes: ["text/csv"] },
  limits: { maxRuntimeSec: 300, maxModelCalls: 3, maxAttempts: 2, maxOutputFiles: 3 },
  approvals: ["None."],
  errors: ["No CSV: refused. An aggregation naming a column that doesn't exist is skipped and reported."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const f = requireInputs(ctx, ["text/csv"], "the data as a CSV file")[0]!;
    const { header, rows } = parseCsv((await readText(ctx, f, 50_000_000)).text);
    if (!header.length || !rows.length) throw new ApiError(400, "bad_request", "The CSV has no rows.");
    const cols = profile(header, rows);
    const profileText = cols.map((c) => `${c.name} (${c.type}): ${c.filled} filled, ${c.missing} missing, ${c.unique} unique${c.type === "number" ? `, min ${c.min}, max ${c.max}, mean ${c.mean}, median ${c.median}` : `, top ${c.top.map((t) => `${t.value}×${t.count}`).join(", ")}`}`).join("\n");
    const Plan = z.object({ groupBy: z.string().nullable().default(null), metric: z.string().nullable().default(null), aggregation: z.enum(["sum", "mean", "count", "median"]).default("sum") });
    const plan = await ctx.models.json("reasoning", Plan, { system: 'Pick the one grouping that best answers the question, using exact column names from the profile (or null). JSON only: {"groupBy","metric","aggregation"}.', prompt: `${untrusted("question", input.brief)}\n\nColumns:\n${untrusted("profile", profileText)}`, maxOutputTokens: 300 });
    const groupOk = plan.groupBy !== null && header.includes(plan.groupBy);
    const metricOk = plan.metric === null || (header.includes(plan.metric) && cols.find((c) => c.name === plan.metric)?.type === "number");
    const agg = groupOk && metricOk ? aggregate(header, rows, plan.groupBy!, plan.metric, plan.metric ? plan.aggregation : "count") : [];
    const aggLabel = groupOk && metricOk ? `${plan.metric ? plan.aggregation : "count"} of ${plan.metric ?? "rows"} by ${plan.groupBy}` : null;
    const narrative = await ctx.models.text("reasoning", { system: "Answer the question from these computed statistics only. Quote numbers exactly as given. Note data quality problems (missing values).", prompt: `${untrusted("question", input.brief)}\n\n${untrusted("profile", profileText)}${aggLabel ? `\n\n${untrusted(aggLabel, agg.slice(0, 50).map((a) => `${a.group}: ${a.value} (n=${a.n})`).join("\n"))}` : ""}`, maxOutputTokens: 1500 });
    const profHeader = ["Column", "Type", "Filled", "Missing", "Unique", "Min", "Max", "Mean", "Median", "Std dev", "Top values"];
    const profRows = cols.map((c) => [c.name, c.type, c.filled, c.missing, c.unique, c.min, c.max, c.mean, c.median, c.stdev, c.top.map((t) => `${t.value} (${t.count})`).join("; ")]);
    const files = [];
    files.push((await deliver(ctx, "analysis.md", "text/markdown", `# Analysis\n\n${rows.length} rows, ${header.length} columns.\n\n${narrative}\n\n## Columns\n\n${mdTable(profHeader, profRows)}\n${aggLabel ? `\n## ${aggLabel}\n\n${mdTable([plan.groupBy!, "Value", "Rows"], agg.slice(0, 100).map((a) => [a.group, a.value, a.n]))}\n` : plan.groupBy ? `\nThe grouping "${plan.groupBy}"/"${plan.metric}" doesn't match the data's columns, so it was skipped.\n` : ""}`)).id);
    files.push((await deliver(ctx, "column-profile.csv", "text/csv", toCsv(profHeader, profRows))).id);
    if (aggLabel) files.push((await deliver(ctx, "aggregation.csv", "text/csv", toCsv([plan.groupBy!, "value", "rows"], agg.map((a) => [a.group, a.value, a.n])))).id);
    const summary = `${rows.length} rows profiled${aggLabel ? `; ${aggLabel}` : ""}.`;
    return finish(ctx, { rows: rows.length, columns: header.length, aggregation: aggLabel, fileIds: files, summary }, summary, files);
  },
});

// ───────────── Finance and Operations Analyst ─────────────

export interface Txn {
  row: number;
  date: string;
  amount: number;
  party: string;
  category: string;
}

export interface Anomaly {
  row: number;
  kind: "duplicate" | "outlier" | "weekend" | "round_large";
  detail: string;
}

/** Possible duplicates (same amount and party within 3 days), robust outliers (beyond 3.5 MADs), weekend and large round payments. */
export function anomalies(txns: Txn[]): Anomaly[] {
  const out: Anomaly[] = [];
  const amounts = txns.map((t) => Math.abs(t.amount));
  const med = median(amounts) ?? 0;
  const mad = median(amounts.map((a) => Math.abs(a - med))) ?? 0;
  const sorted = [...txns].sort((a, b) => a.date.localeCompare(b.date));
  for (let i = 0; i < sorted.length; i++) {
    const t = sorted[i]!;
    for (let j = i + 1; j < sorted.length; j++) {
      const u = sorted[j]!;
      if (Date.parse(u.date) - Date.parse(t.date) > 3 * 86_400_000) break;
      if (u.amount === t.amount && u.party.toLowerCase() === t.party.toLowerCase() && t.party) out.push({ row: u.row, kind: "duplicate", detail: `Same amount ${u.amount} to ${u.party} as row ${t.row}, within 3 days` });
    }
    if (mad > 0 && Math.abs(Math.abs(t.amount) - med) / (1.4826 * mad) > 3.5) out.push({ row: t.row, kind: "outlier", detail: `${t.amount} is far from the typical ${round(med)}` });
    const day = new Date(t.date).getUTCDay();
    if (!Number.isNaN(day) && (day === 0 || day === 6) && t.amount < 0) out.push({ row: t.row, kind: "weekend", detail: `Payment on a ${day === 0 ? "Sunday" : "Saturday"}` });
    if (Math.abs(t.amount) >= 1000 && Math.abs(t.amount) % 100 === 0) out.push({ row: t.row, kind: "round_large", detail: `Large round amount ${t.amount}` });
  }
  return out;
}

const FinanceOutput = z.strictObject({ transactions: z.number().int(), anomalies: z.number().int(), months: z.number().int(), fileIds: z.array(z.string()), paymentsMade: z.literal(0), summary: Summary });

export const financeSkill = defineSkill<{ brief: string }, z.infer<typeof FinanceOutput>>({
  id: "finance.analyze",
  version: 1,
  input: z.strictObject({ brief: Brief }),
  output: FinanceOutput,
  tools: ["files.read", "files.write", "channel.post", "payments.execute"],
  requiredTools: ["files.read", "files.write"],
  categories: ["reasoning"],
  requiredCategories: ["reasoning"],
  files: { min: 1, max: 3, maxBytes: 50 * MB, mimes: ["text/csv"] },
  limits: { maxRuntimeSec: 300, maxModelCalls: 2, maxAttempts: 2, maxOutputFiles: 3 },
  approvals: ["Payments and transfers (payments.execute) are never allowed for agents on this platform."],
  errors: ["No CSV, or no date and amount columns: refused with the names it looks for."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const f = requireInputs(ctx, ["text/csv"], "the transactions as a CSV file")[0]!;
    const { header, rows } = parseCsv((await readText(ctx, f, 50_000_000)).text);
    const c = { date: column(header, ["date", "transaction date", "posted", "booking date"]), amount: column(header, ["amount", "value", "debit", "total"]), party: column(header, ["counterparty", "payee", "vendor", "merchant", "description", "name"]), category: column(header, ["category", "account", "type"]) };
    if (c.date < 0 || c.amount < 0) throw new ApiError(400, "bad_request", "The CSV needs a date and an amount column (also used: counterparty or description, category).");
    const txns: Txn[] = rows.flatMap((r, i) => {
      const amount = num(r[c.amount]);
      const date = (r[c.date] ?? "").trim();
      if (amount === null || Number.isNaN(Date.parse(date))) return [];
      return [{ row: i + 2, date: new Date(date).toISOString().slice(0, 10), amount, party: c.party >= 0 ? (r[c.party] ?? "").trim() : "", category: c.category >= 0 ? (r[c.category] ?? "").trim() || "(none)" : "(none)" }];
    });
    if (!txns.length) throw new ApiError(400, "bad_request", "No row has a readable date and amount.");
    const sum = (key: (t: Txn) => string) => [...txns.reduce((m, t) => m.set(key(t), (m.get(key(t)) ?? 0) + t.amount), new Map<string, number>()).entries()].map(([k, v]) => [k, round(v)] as const).sort((a, b) => a[0].localeCompare(b[0]));
    const byMonth = sum((t) => t.date.slice(0, 7));
    const byCategory = sum((t) => t.category).sort((a, b) => a[1] - b[1]);
    const found = anomalies(txns);
    const narrative = await ctx.models.text("reasoning", {
      system: "Write a short finance briefing from these computed figures only: trends, the categories that matter, and which flagged rows to check first. Don't compute new figures.",
      prompt: `${untrusted("brief", input.brief)}\n\n${untrusted("figures", `By month: ${byMonth.map(([k, v]) => `${k} ${v}`).join("; ")}\nBy category: ${byCategory.map(([k, v]) => `${k} ${v}`).join("; ")}\nFlags (${found.length}): ${found.slice(0, 60).map((a) => `row ${a.row} ${a.kind}: ${a.detail}`).join("; ")}`)}`,
      maxOutputTokens: 1500,
    });
    const anomalyFile = await deliver(ctx, "flagged-transactions.csv", "text/csv", toCsv(["Row", "Kind", "Detail"], found.map((a) => [a.row, a.kind, a.detail])));
    const report = await deliver(ctx, "finance-report.md", "text/markdown", `# Finance report\n\n${txns.length} transactions, ${byMonth.length} months.\n\n${narrative}\n\n## By month\n\n${mdTable(["Month", "Net"], byMonth.map(([k, v]) => [k, v]))}\n\n## By category\n\n${mdTable(["Category", "Net"], byCategory.map(([k, v]) => [k, v]))}\n\n## Flagged (${found.length})\n\nSee flagged-transactions.csv. Flags are prompts to check, not findings of wrongdoing.\n\nNo payment or transfer was made: agents can't make them.\n`);
    const summary = `${txns.length} transactions over ${byMonth.length} months; ${found.length} flagged for review.`;
    return finish(ctx, { transactions: txns.length, anomalies: found.length, months: byMonth.length, fileIds: [report.id, anomalyFile.id], paymentsMade: 0, summary }, summary, [report.id, anomalyFile.id]);
  },
});

// ───────────── Meeting and Documentation ─────────────

const Minutes = z.object({
  title: z.string().max(150),
  summary: z.string().max(4000),
  decisions: z.array(z.string().max(500)).max(30).default([]),
  actionItems: z.array(z.object({ task: z.string().min(1).max(400), owner: z.string().max(80).default(""), due: z.string().max(40).default("") })).max(50).default([]),
  openQuestions: z.array(z.string().max(400)).max(20).default([]),
});
const MeetingOutput = z.strictObject({ transcribed: z.boolean(), decisions: z.number().int(), actionItems: z.number().int(), fileIds: z.array(z.string()), summary: Summary });

export const meetingSkill = defineSkill<{ brief: string }, z.infer<typeof MeetingOutput>>({
  id: "meeting.docs",
  version: 1,
  input: z.strictObject({ brief: Brief }),
  output: MeetingOutput,
  tools: ["files.read", "files.write", "channel.post", "media.probe", "media.extract_audio"],
  requiredTools: ["files.read", "files.write"],
  categories: ["reasoning", "transcription"],
  requiredCategories: ["reasoning"],
  files: { min: 1, max: 5, maxBytes: 300 * MB, mimes: [...TEXT_MIMES, ...AUDIO_MIMES, ...VIDEO_MIMES] },
  limits: { maxRuntimeSec: 900, maxModelCalls: 8, maxAttempts: 2, maxOutputFiles: 3 },
  approvals: ["None: it writes minutes."],
  errors: [
    "A recording without a speech-to-text model configured is refused; send a transcript instead.",
    "Recordings need the media engine (their sound is extracted as a small MP3); over 25 MB of that MP3 (about 70 minutes) is refused by the gateway's limit.",
  ],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const texts = await readTexts(ctx, TEXT_MIMES, 150_000);
    const recordings = ctx.inputs.filter((f) => (AUDIO_MIMES as readonly string[]).includes(f.mime) || (VIDEO_MIMES as readonly string[]).includes(f.mime));
    if (!texts.length && !recordings.length) throw new ApiError(400, "bad_request", "Send a transcript, notes or a recording of the meeting.");
    if (recordings.length && !ctx.models.available("transcription")) throw new ApiError(503, "ai_not_configured", "No speech-to-text model is configured. Send a transcript instead.");
    for (const r of recordings) {
      ctx.progress(10, `Transcribing ${r.name}`);
      // Audio and video alike go through the media engine: a small mono MP3, never the whole recording in memory.
      const audio = await ctx.tool("media.extract_audio", { fileId: r.id });
      const t = await ctx.models.transcribe({ bytes: audio.bytes, mime: audio.mime, name: r.name, seconds: audio.seconds });
      texts.push({ id: r.id, name: `${r.name} (transcript)`, text: t.text });
    }
    ctx.progress(60, "Writing the minutes");
    const m = await ctx.models.json("reasoning", Minutes, {
      system: 'Write meeting minutes from the material only. Owners and dates only when stated. JSON only: {"title","summary","decisions","actionItems":[{"task","owner","due"}],"openQuestions"}.',
      prompt: `${untrusted("request", input.brief)}\n\n${texts.map((s) => untrusted(s.name, s.text)).join("\n\n")}`,
      maxOutputTokens: 4000,
    });
    const md = await deliver(ctx, "meeting-minutes.md", "text/markdown", `# ${m.title}\n\n${m.summary}\n\n## Decisions\n\n${m.decisions.map((d) => `- ${d}`).join("\n") || "None recorded."}\n\n## Action items\n\n${m.actionItems.length ? mdTable(["Task", "Owner", "Due"], m.actionItems.map((a) => [a.task, a.owner, a.due])) : "None."}\n\n## Open questions\n\n${m.openQuestions.map((q) => `- ${q}`).join("\n") || "None."}\n`);
    const files = [md.id];
    if (m.actionItems.length) files.push((await deliver(ctx, "action-items.csv", "text/csv", toCsv(["Task", "Owner", "Due"], m.actionItems.map((a) => [a.task, a.owner, a.due])))).id);
    const summary = `Minutes "${m.title}": ${m.decisions.length} decisions, ${m.actionItems.length} action items.`;
    return finish(ctx, { transcribed: recordings.length > 0, decisions: m.decisions.length, actionItems: m.actionItems.length, fileIds: files, summary }, summary, files);
  },
});
