import crypto from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { anomalies, aggregate, profile } from "../src/modules/workforce/catalog/skills/analysis.js";
import { icsEvent, stockReport } from "../src/modules/workforce/catalog/skills/business.js";
import { LENGTHS, mask, parseSrt, unmask, words } from "../src/modules/workforce/catalog/skills/content.js";
import { checkPatch, failureLines } from "../src/modules/workforce/catalog/skills/engineering.js";
import { checkPlan as checkManagerPlan } from "../src/modules/workforce/catalog/skills/manager.js";
import { auditHtml, campaignMetrics, fits } from "../src/modules/workforce/catalog/skills/marketing.js";
import { assignShifts, schedule } from "../src/modules/workforce/catalog/skills/planning.js";
import { fallbackClips, mapCaptions, normaliseClips } from "../src/modules/workforce/catalog/skills/video.js";
import { parseCsv, quoteFound, rank, passages, toCsv } from "../src/modules/workforce/catalog/common.js";
import { AGENTS } from "../src/modules/workforce/index.js";
import { MemoryMailer } from "./support/accounts.js";
import { ADMIN_ORIGIN, consoleAdmin } from "./support/adminConsole.js";
import { chatText, type FakeCall, fakeProviders, openAiChat } from "./support/fakeAi.js";
import { type Harness, startServer } from "./support/harness.js";
import { type Community, community, configurePlatform, failure } from "./support/workforce.js";

/**
 * Every catalog agent, each with its own skill: the deterministic parts (numbers, quotes, structure, schedules,
 * calendars, subtitles) are unit-tested, and each agent runs once end to end through the API with a fake provider
 * answering for its skill. These prove the skills' code paths and checks, not the quality of any real model.
 */

describe("skill checks (deterministic)", () => {
  it("CSV: quoted fields, formula neutralising", () => {
    expect(parseCsv('a,b\n"x, y","he said ""hi"""\n1,2\n')).toEqual({ header: ["a", "b"], rows: [["x, y", 'he said "hi"'], ["1", "2"]] });
    expect(toCsv(["v"], [["=HYPERLINK(1)"], [-3], ["-x"]])).toBe("v\n'=HYPERLINK(1)\n-3\n'-x\n");
  });

  it("quotes must really be in their source", () => {
    expect(quoteFound("The contract ends   after 30 days.", "Clause 4. The contract ends after 30 days. Clause 5.")).toBe(true);
    expect(quoteFound("The contract ends after 90 days.", "The contract ends after 30 days.")).toBe(false);
    expect(quoteFound("short", "short")).toBe(false);
    const ps = passages([{ id: "s1", name: "a", text: "Apples are red.\n\nBananas are yellow and long." }], 20);
    expect(rank("yellow bananas", ps, 1)[0]!.text).toContain("Bananas");
  });

  it("patches must apply to the files sent", () => {
    const files = new Map([["src/cart.ts", "export function total(items) {\n  return items.length ? sum(items) : null;\n}\n"]]);
    const good = "--- a/src/cart.ts\n+++ b/src/cart.ts\n@@ -1,3 +1,3 @@\n export function total(items) {\n-  return items.length ? sum(items) : null;\n+  return items.length ? sum(items) : 0;\n }\n";
    expect(checkPatch(good, files)).toEqual([]);
    expect(checkPatch(good.replace("return items.length ? sum(items) : null;", "return nothing;"), files)).toEqual(["src/cart.ts: line 2 doesn't match the hunk"]);
    expect(checkPatch(good.replace(/cart\.ts/g, "other.ts"), files)[0]).toContain("not one of the files sent");
    expect(failureLines("ok\nERROR boom\nfine\nTypeError: x is undefined")).toEqual([
      { line: 2, text: "ERROR boom" },
      { line: 4, text: "TypeError: x is undefined" },
    ].slice(0, 1));
  });

  it("project plans: cycles and unknown dependencies removed, critical path computed", () => {
    const r = schedule([
      { id: "a", title: "A", estimateHours: 4, dependsOn: [], owner: null },
      { id: "b", title: "B", estimateHours: 2, dependsOn: ["a", "zz"], owner: null },
      { id: "c", title: "C", estimateHours: 10, dependsOn: ["a"], owner: null },
      { id: "d", title: "D", estimateHours: 1, dependsOn: ["b", "c"], owner: null },
      { id: "a2", title: "A again", estimateHours: 1, dependsOn: ["d"], owner: null },
    ].concat([{ id: "a", title: "dup", estimateHours: 1, dependsOn: ["d"], owner: null }]));
    expect(r.totalHours).toBe(16);
    expect(r.tasks.filter((t) => t.critical).map((t) => t.id)).toEqual(["a", "c", "d", "a2"]);
    expect(r.removed).toEqual(expect.arrayContaining(["b → zz (unknown)", "1 duplicate task ids"]));
    const cyc = schedule([
      { id: "x", title: "X", estimateHours: 1, dependsOn: ["y"], owner: null },
      { id: "y", title: "Y", estimateHours: 1, dependsOn: ["x"], owner: null },
    ]);
    expect(cyc.tasks).toHaveLength(2);
    expect(cyc.removed.some((x) => x.includes("cycle"))).toBe(true);
  });

  it("shift schedules respect availability, hours and overlaps, and list gaps", () => {
    const r = assignShifts({
      people: [
        { name: "Ana", maxHours: 8, unavailable: ["tue"] },
        { name: "Ben", maxHours: 16, unavailable: [] },
      ],
      shifts: [
        { day: "mon", start: "09:00", end: "17:00", needed: 2 },
        { day: "tue", start: "09:00", end: "17:00", needed: 2 },
        { day: "mon", start: "12:00", end: "14:00", needed: 1 },
      ],
    });
    expect(r.assignments.map((a) => [a.day, a.start, a.people, a.missing])).toEqual([
      ["mon", "09:00", ["Ana", "Ben"], 0],
      ["mon", "12:00", [], 1],
      ["tue", "09:00", ["Ben"], 1],
    ]);
  });

  it("campaign metrics, page audits, post limits", () => {
    const m = campaignMetrics("Campaign,Spend,Impressions,Clicks,Conversions,Revenue\nA,100,10000,200,10,500\nA,50,5000,100,5,250\nB,80,8000,10,0,0\n").rows;
    expect(m.find((r) => r.campaign === "A")).toMatchObject({ spend: 150, ctr: 2, cpc: 0.5, cpa: 10, roas: 5 });
    expect(m.find((r) => r.campaign === "B")).toMatchObject({ conversions: 0, cpa: null, roas: 0 });
    const a = auditHtml("p", '<html><head><title>Hi</title><meta name="robots" content="noindex"></head><body><h1>A</h1><h1>B</h1><h3>c</h3><img src=x></body></html>');
    expect(a.issues.map((i) => i.check)).toEqual(expect.arrayContaining(["title", "meta description", "indexing", "mobile", "language", "canonical", "h1", "headings", "images", "content"]));
    expect(fits({ platform: "x", text: "x".repeat(270), hashtags: ["one", "two"], when: "", visual: "" })).toMatchObject({ ok: false });
    expect(fits({ platform: "linkedin", text: "hello", hashtags: [], when: "", visual: "" })).toMatchObject({ ok: true, hashtagsOk: true });
  });

  it("inventory, data profiles, finance anomalies", () => {
    const s = stockReport("SKU,Name,Qty,Price\nA1,Bottle,0,10\nA2,Cap,5,2\nA3,Box,50,1\nA4,Odd,n/a,1\n").lines;
    expect(s.map((l) => l.status)).toEqual(["out_of_stock", "low", "ok", "unknown"]);
    const { header, rows } = parseCsv("region,revenue\nNorth,10\nSouth,5\nNorth,7\n");
    expect(profile(header, rows)[1]).toMatchObject({ type: "number", min: 5, max: 10, mean: 7.3333, median: 7 });
    expect(aggregate(header, rows, "region", "revenue", "sum")).toEqual([
      { group: "North", value: 17, n: 2 },
      { group: "South", value: 5, n: 1 },
    ]);
    const tx = [
      { row: 2, date: "2026-10-01", amount: -120, party: "Acme", category: "x" },
      { row: 3, date: "2026-10-02", amount: -120, party: "acme", category: "x" },
      { row: 4, date: "2026-10-03", amount: -100, party: "B", category: "x" },
      { row: 5, date: "2026-10-04", amount: -5000, party: "C", category: "x" },
      { row: 6, date: "2026-10-05", amount: -110, party: "D", category: "x" },
    ];
    const kinds = anomalies(tx).map((x) => `${x.row}:${x.kind}`);
    expect(kinds).toEqual(expect.arrayContaining(["3:duplicate", "5:outlier", "5:round_large"]));
  });

  it("calendar files and subtitles keep their structure", () => {
    const ics = icsEvent({ title: "Review, design", start: "2026-11-03T15:00:00+01:00", durationMinutes: 45, location: "", description: "Line one\nline two", attendees: ["a@b.co", "not-an-email"] }, new Date("2026-10-24T00:00:00Z"));
    expect(ics).toContain("DTSTART:20261103T140000Z\r\nDTEND:20261103T144500Z");
    expect(ics).toContain("SUMMARY:Review\\, design");
    expect(ics).toContain("ATTENDEE;RSVP=TRUE:mailto:a@b.co");
    expect(ics).not.toContain("not-an-email");
    expect(() => icsEvent({ title: "x", start: "next tuesday", durationMinutes: 30, location: "", description: "", attendees: [] }, new Date())).toThrow();
    const srt = "1\n00:00:01,000 --> 00:00:02,500\nHello there\n\n2\n00:00:03,000 --> 00:00:04,000\nSecond line\nwraps\n";
    expect(parseSrt(srt)).toEqual([
      { timing: "00:00:01,000 --> 00:00:02,500", text: "Hello there" },
      { timing: "00:00:03,000 --> 00:00:04,000", text: "Second line\nwraps" },
    ]);
    const m = mask("Run `npm test` at https://x.test/a and {{name}}.");
    expect(m.tokens).toEqual(["`npm test`", "https://x.test/a", "{{name}}"]);
    expect(unmask(m.masked.replace("Run", "Ejecuta"), m.tokens)).toBe("Ejecuta `npm test` at https://x.test/a and {{name}}.");
    expect(unmask("Ejecuta ⟦0⟧ y ⟦2⟧.", m.tokens)).toBeNull();
    expect(words("It's a well-known fact, 42 times.")).toBe(6);
    expect(LENGTHS.short[0]).toBeLessThan(LENGTHS.short[1]);
  });

  it("video plans stay inside the footage; captions move onto the output timeline", () => {
    const infos = new Map([
      ["f1", { durationSec: 6, hasVideo: true, hasAudio: true, width: 320, height: 240 }],
      ["f2", { durationSec: 6, hasVideo: true, hasAudio: false, width: 320, height: 240 }],
      ["f3", { durationSec: 3, hasVideo: false, hasAudio: true, width: null, height: null }],
    ]);
    const clips = normaliseClips(
      [
        { fileId: "f1", startSec: -1, endSec: 4, reason: "a" },
        { fileId: "f3", startSec: 0, endSec: 2, reason: "audio only" },
        { fileId: "nope", startSec: 0, endSec: 2, reason: "unknown" },
        { fileId: "f2", startSec: 2, endSec: 99, reason: "b" },
        { fileId: "f2", startSec: 1, endSec: 1.2, reason: "sliver" },
      ],
      infos,
      6,
    );
    expect(clips.map((c) => [c.fileId, c.startSec, c.endSec])).toEqual([
      ["f1", 0, 4],
      ["f2", 2, 6],
    ]);
    expect(fallbackClips(infos, 8).every((c) => c.fileId !== "f3" && c.endSec <= 6)).toBe(true);
    const caps = mapCaptions(clips, new Map([["f1", [{ start: 3, end: 5, text: "spans the cut" }]], ["f2", [{ start: 2.5, end: 3, text: "second" }]]]));
    expect(caps).toEqual([
      { start: 3, end: 4, text: "spans the cut" },
      { start: 4.5, end: 5, text: "second" },
    ]);
  });

  it("Agent Manager plans: known agents only, earlier dependencies only", () => {
    const team = new Set(["agi_a", "agi_b"]);
    const plan = (steps: { key: string; installId: string; task: string; dependsOn: string[] }[]) => ({ mode: "delegate" as const, answer: null, question: null, steps });
    expect(checkManagerPlan(plan([{ key: "1", installId: "agi_a", task: "t", dependsOn: [] }, { key: "2", installId: "agi_b", task: "t", dependsOn: ["1"] }]), team).ok).toBe(true);
    expect(checkManagerPlan(plan([{ key: "1", installId: "agi_x", task: "t", dependsOn: [] }]), team)).toMatchObject({ ok: false });
    expect(checkManagerPlan(plan([{ key: "1", installId: "agi_a", task: "t", dependsOn: ["2"] }, { key: "2", installId: "agi_b", task: "t", dependsOn: ["1"] }]), team)).toMatchObject({ ok: false });
  });

  it("every agent has its own skill, contract and documented behaviour", () => {
    expect(AGENTS).toHaveLength(21);
    expect(new Set(AGENTS.map((a) => a.skill.id)).size).toBe(21);
    for (const a of AGENTS) {
      expect(a.capabilities.length, a.id).toBeGreaterThan(0);
      expect(a.skill.errors.length, a.id).toBeGreaterThan(0);
      expect(a.skill.approvals.length, a.id).toBeGreaterThan(0);
      expect(a.skill.tests.length, a.id).toBeGreaterThan(0);
      expect(a.skill.requiredTools.every((t) => a.skill.tools.includes(t)), a.id).toBe(true);
      for (const i of a.integrations) expect(a.skill.tools, a.id).toContain(i.tool);
      expect(a.skill.input.safeParse({ brief: "A request." }).success, `${a.id} takes a plain brief (the Agent Manager sends one)`).toBe(true);
    }
  });
});

// ───────────── Each agent end to end ─────────────

const KB = "Refunds are issued within 14 days of purchase. Shipping to the EU takes three to five working days.";
const CODE = "export function total(items) {\n  return items.length ? sum(items) : null;\n}\n";
const PATCH = "--- a/cart.ts\n+++ b/cart.ts\n@@ -1,3 +1,3 @@\n export function total(items) {\n-  return items.length ? sum(items) : null;\n+  return items.length ? sum(items) : 0;\n }\n";

function skillAnswer(c: FakeCall) {
  if (c.url.endsWith("/v1/models")) return { status: 200, body: { data: [{ id: "gpt-test" }] } };
  if (!c.url.endsWith("/v1/chat/completions")) return null;
  const { system, user } = chatText(c);
  const json = (v: unknown) => openAiChat(JSON.stringify(v));
  if (user.includes("Reply with the single word")) return openAiChat("pong");
  if (system.includes("Write a research report")) {
    const src = /<untrusted source="(aif_[A-Za-z0-9_-]+)#/.exec(user)?.[1] ?? "x";
    return json({ title: "Refund policy", sections: [{ heading: "Refunds", paragraphs: [{ text: "Refunds take two weeks.", citations: [{ source: src, quote: "Refunds are issued within 14 days of purchase." }, { source: src, quote: "Refunds are always instant and free." }] }] }], gaps: ["Exchanges"] });
  }
  if (system.includes("You're a senior engineer")) return json({ overview: "Returns null for empty carts.", findings: [{ file: "cart.ts", line: 2, severity: "high", issue: "null for empty", fix: "return 0" }], patch: PATCH, documentation: null });
  if (system.includes("Write a test plan")) return json({ cases: [{ title: "Empty cart", area: "cart", preconditions: "", steps: ["Open cart"], expected: "Total 0", priority: "P0" }, { title: "One item", steps: ["Add item"], expected: "Total is price", priority: "P1" }] });
  if (system.includes("Break the goal into concrete tasks")) return json({ tasks: [{ id: "a", title: "Design", estimateHours: 6, dependsOn: [] }, { id: "b", title: "Build", estimateHours: 12, dependsOn: ["a", "c"] }, { id: "c", title: "Test", estimateHours: 4, dependsOn: ["b"] }] });
  if (system.includes("Write a article") || system.includes("Write a announcement")) return json({ title: "Voice channels", body: Array.from({ length: 300 }, (_, i) => `w${i}`).join(" "), headlines: ["Talk more"] });
  if (system.includes("Turn the brief into a design concept")) return json({ concept: "Warm, bold.", palette: ["#FF7700"], typography: "Sans", images: [{ prompt: "A bottle on a warm background", alt: "Bottle" }] });
  if (system.includes("social posts")) {
    if (user.includes("over their platform's limits")) return json({ posts: [{ platform: "x", text: "Short now.", hashtags: ["acme"] }] });
    return json({ posts: [{ platform: "x", text: "y".repeat(300), hashtags: ["acme"] }, { platform: "linkedin", text: "Launch day!", hashtags: ["launch"] }] });
  }
  if (system.includes("performance marketer")) return json({ recommendations: [{ campaign: "B", action: "Pause B", rationale: "No conversions" }], experiments: [] });
  if (system.includes("Prioritise SEO fixes")) return json({ priorities: [{ action: "Add a meta description", why: "Missing", effort: "low" }] });
  if (system.includes("inventory briefing")) return openAiChat("Reorder the bottle first.");
  if (system.includes("You're a support agent")) {
    const src = /<untrusted source="(aif_[A-Za-z0-9_-]+)#/.exec(user)?.[1] ?? "x";
    return json({ category: "refunds", priority: "normal", sentiment: "neutral", reply: "You'll get your refund within 14 days.", citations: [{ source: src, quote: "Refunds are issued within 14 days of purchase." }], escalate: false, escalationReason: null });
  }
  if (system.includes("outreach emails")) return json({ drafts: [{ audience: "Community managers", subject: "Moderation that scales", body: "Hi, ..." }] });
  if (system.includes("Pick the one grouping")) return json({ groupBy: "region", metric: "revenue", aggregation: "sum" });
  if (system.includes("Answer the question from these computed statistics")) return openAiChat("North leads.");
  if (system.includes("finance briefing")) return openAiChat("Check row 3.");
  if (system.includes("Write meeting minutes")) return json({ title: "Launch sync", summary: "We agreed the date.", decisions: ["Launch on Nov 3"], actionItems: [{ task: "Book venue", owner: "Ana", due: "Oct 30" }], openQuestions: [] });
  if (system.includes("Extract one meeting")) return json({ title: "Design review", start: "2026-11-03T15:00:00+01:00", durationMinutes: 45, attendees: ["ana@example.com"] });
  if (system.includes("Translate subtitle cues")) {
    const cues = JSON.parse(/\[[\s\S]*\]/.exec(user)![0]) as { i: number; text: string }[];
    return json({ cues: cues.map((q) => ({ i: q.i, text: `ES ${q.text}` })) });
  }
  if (system.includes("onboarding plan")) return json({ title: "Onboarding", sections: [{ heading: "Week 1", body: "Meet the team." }], checklist: [{ when: "Day 1", item: "Accounts", owner: "IT" }] });
  return openAiChat("Here is the answer.");
}

const mailer = new MemoryMailer();
let h: Harness;
let c: Community;
const installs = new Map<string, string>();

beforeAll(async () => {
  fakeProviders(skillAnswer);
  h = await startServer({ ADMIN_CONSOLE_ORIGIN: ADMIN_ORIGIN, PLATFORM_SECRETS_KEY: crypto.randomBytes(32).toString("hex"), AI_WORKFORCE_TICK_MS: "0" }, { mailer });
  const admin = await consoleAdmin(h, mailer, "super_admin", "skillsuper");
  await configurePlatform(admin, { models: [{ model: "gpt-test", capabilities: ["text"] }], agents: AGENTS.map((a) => a.id) });
  c = await community(h, "Skills");
  for (const a of AGENTS) installs.set(a.id, (await c.owner.req("POST", `/v1/orgs/${c.orgId}/ai/agents`, { agentId: a.id })).agent.id);
}, 180_000);

afterAll(async () => h.close());

async function runAgent(agentId: string, input: Record<string, unknown>, files: [string, string, string | Buffer][] = []) {
  const fileIds = [];
  for (const [name, type, body] of files) fileIds.push((await c.member.req("POST", `/v1/orgs/${c.orgId}/ai/files?name=${name}&type=${type}`, undefined, Buffer.isBuffer(body) ? body : Buffer.from(body))).file.id);
  const job = (await c.member.req("POST", `/v1/orgs/${c.orgId}/ai/jobs`, { installId: installs.get(agentId), input, fileIds })).job;
  await h.ctx.workforce.runtime.runUntilIdle();
  const d = (await c.member.req("GET", `/v1/orgs/${c.orgId}/ai/jobs/${job.id}`)).job;
  if (d.state !== "completed") throw new Error(`${agentId}: ${d.state} ${JSON.stringify(d.error)}`);
  const text = async (fileId: string) => (await c.member.req("GET", `/v1/orgs/${c.orgId}/ai/files/${fileId}?download=1`)).toString("utf8");
  return { d, out: d.output, text, files: d.files as { id: string; name: string; mime: string; kind: string }[] };
}

describe("each agent end to end", () => {
  it("General Assistant", async () => {
    expect((await runAgent("general_assistant", { brief: "Hello?" })).out.answer).toBe("Here is the answer.");
  });

  it("Research and Knowledge keeps only citations found in the sources", async () => {
    const r = await runAgent("research_knowledge", { brief: "How long do refunds take?" }, [["policy.md", "text/markdown", KB]]);
    expect(r.out.citations).toEqual({ verified: 1, dropped: 1 });
    expect(await r.text(r.out.reportFileId)).toContain("1 verified, 1 dropped");
  });

  it("Coding and Engineering checks that its patch applies", async () => {
    const r = await runAgent("coding_engineering", { brief: "Empty carts return null", task: "patch" }, [["cart.ts", "text/plain", CODE]]);
    expect(r.out.patch).toMatchObject({ applies: true, problems: [] });
    expect(await r.text(r.out.patch.fileId)).toBe(PATCH);
  });

  it("QA and Testing numbers its cases", async () => {
    const r = await runAgent("qa_testing", { brief: "Checkout" });
    expect(r.out.cases).toBe(2);
    expect(await r.text(r.files.find((f) => f.mime === "text/csv")!.id)).toContain("TC-001,P0");
  });

  it("Project Manager removes the dependency cycle it was given", async () => {
    const r = await runAgent("project_manager", { brief: "Ship it" });
    expect(r.out.removedDependencies.some((x: string) => x.includes("cycle"))).toBe(true);
    expect(r.out.tasks).toBe(3);
  });

  it("Content Writer measures the length", async () => {
    const r = await runAgent("content_writer", { brief: "Voice channels", format: "article", length: "medium" });
    expect(r.out).toMatchObject({ words: 300, withinLength: true });
  });

  it("Graphic and Image Designer says when images can't be generated", async () => {
    const r = await runAgent("graphic_designer", { brief: "A thumbnail" });
    expect(r.out).toMatchObject({ generated: false, images: [] });
    expect(await r.text(r.out.briefFileId)).toContain("No image-generation model is configured");
  });

  it("Social Media Manager rewrites posts over their limit and never publishes", async () => {
    const r = await runAgent("social_media_manager", { brief: "Launch", platforms: ["x", "linkedin"], posts: 2 });
    expect(r.out).toMatchObject({ posts: 2, overLimit: 0, published: false });
  });

  it("Performance Marketing computes the metrics itself", async () => {
    const r = await runAgent("performance_marketing", { brief: "What to cut?" }, [["ads.csv", "text/csv", "Campaign,Spend,Impressions,Clicks,Conversions,Revenue\nA,100,10000,200,10,500\nB,80,8000,10,0,0\n"]]);
    expect(r.out.totals).toEqual({ spend: 180, revenue: 500, conversions: 10, roas: 2.78 });
    expect(r.out.flags).toEqual(expect.arrayContaining(["B: 80 spent, no conversions"]));
  });

  it("SEO audits the page it was sent", async () => {
    const r = await runAgent("seo_growth", { brief: "Fix first?" }, [["index.html", "text/html", "<html><head><title>Acme</title></head><body><p>Hi</p></body></html>"]]);
    expect(r.out.issues.high).toBeGreaterThanOrEqual(2);
  });

  it("E-commerce Operations reports stock", async () => {
    const r = await runAgent("ecommerce_operations", { brief: "Reorder?" }, [["stock.csv", "text/csv", "SKU,Name,Qty,Price\nA1,Bottle,0,10\nA2,Cap,5,2\nA3,Box,50,1\n"]]);
    expect(r.out).toMatchObject({ products: 3, outOfStock: 1, lowStock: 1 });
  });

  it("Customer Support drafts from the knowledge base and doesn't send", async () => {
    const r = await runAgent("customer_support", { brief: "When do I get my refund?" }, [["kb.md", "text/markdown", KB]]);
    expect(r.out).toMatchObject({ verifiedCitations: 1, escalate: false, sent: false });
  });

  it("Sales and CRM drafts outreach", async () => {
    expect((await runAgent("sales_crm", { brief: "Moderation tools", drafts: 1 })).out.items).toBe(1);
  });

  it("Data and Analytics computes the grouping", async () => {
    const r = await runAgent("data_analytics", { brief: "Which region?" }, [["sales.csv", "text/csv", "region,revenue\nNorth,10\nSouth,5\nNorth,7\n"]]);
    expect(r.out.aggregation).toBe("sum of revenue by region");
    expect(await r.text(r.files.find((f) => f.name === "aggregation.csv")!.id)).toBe("region,value,rows\nNorth,17,2\nSouth,5,1\n");
  });

  it("HR and Workplace Operations schedules shifts without a model", async () => {
    const r = await runAgent("hr_workplace_ops", {
      brief: "Shifts",
      task: "shift_schedule",
      roster: { people: [{ name: "Ana", maxHours: 8, unavailable: [] }], shifts: [{ day: "mon", start: "09:00", end: "17:00", needed: 2 }] },
    });
    expect(r.out.gaps).toBe(1);
  });

  it("Finance and Operations flags duplicates and never pays", async () => {
    const r = await runAgent("finance_operations", { brief: "Anything odd?" }, [["tx.csv", "text/csv", "date,amount,payee\n2026-10-01,-120,Acme\n2026-10-02,-120,Acme\n2026-10-03,-40,Cafe\n"]]);
    expect(r.out).toMatchObject({ transactions: 3, paymentsMade: 0 });
    expect(await r.text(r.files.find((f) => f.name === "flagged-transactions.csv")!.id)).toContain("duplicate");
  });

  it("Meeting and Documentation writes minutes and action items", async () => {
    const r = await runAgent("meeting_documentation", { brief: "Launch sync" }, [["notes.txt", "text/plain", "Ana: let's launch Nov 3. Ana books the venue."]]);
    expect(r.out).toMatchObject({ decisions: 1, actionItems: 1, transcribed: false });
  });

  it("Email and Calendar makes a valid calendar file", async () => {
    const r = await runAgent("email_calendar", { brief: "Design review Tuesday 3pm CET", task: "schedule_meeting" });
    const ics = await r.text(r.out.fileIds[0]);
    expect(ics).toContain("DTSTART:20261103T140000Z");
    expect(ics).toContain("ATTENDEE;RSVP=TRUE:mailto:ana@example.com");
  });

  it("Translation keeps every subtitle cue and timing", async () => {
    const srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n\n2\n00:00:03,000 --> 00:00:04,500\nBye\n";
    const r = await runAgent("translation_localization", { brief: "Into Spanish", targetLanguage: "Spanish" }, [["captions.srt", "text/plain", srt]]);
    expect(r.out.files[0]).toMatchObject({ kind: "subtitles", units: 2 });
    expect(await r.text(r.out.files[0].fileId)).toBe("1\n00:00:01,000 --> 00:00:02,000\nES Hello\n\n2\n00:00:03,000 --> 00:00:04,500\nES Bye\n");
  });

  it("Product Video Editor refuses without footage (the full flow is the acceptance suite)", async () => {
    const r = await failure(c.member.req("POST", `/v1/orgs/${c.orgId}/ai/jobs`, { installId: installs.get("product_video_editor"), input: { brief: "x" } }));
    expect(r.status).toBe(400);
    expect(r.body.error.message).toContain("needs a file");
  });
});
