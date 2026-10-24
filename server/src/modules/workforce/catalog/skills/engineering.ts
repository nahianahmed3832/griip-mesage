import { z } from "zod/v4";
import { ApiError } from "../../../../shared/errors.js";
import { untrusted } from "../../prompting.js";
import { defineSkill } from "../../types.js";
import { Brief, deliver, finish, mdTable, MB, readTexts, requireInputs, type Source, Summary, toCsv } from "../common.js";

const CODE_MIMES = ["text/plain", "text/markdown", "application/json", "text/csv", "text/html"] as const;

/**
 * Checks a unified diff against the files it names: every hunk's context and removed lines must be exactly where the
 * hunk says. Returns the problems (none: it applies).
 */
export function checkPatch(diff: string, files: Map<string, string>): string[] {
  const problems: string[] = [];
  const lines = diff.split("\n");
  let target: string[] | null = null;
  let name = "";
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i]!;
    if (l.startsWith("+++ ")) {
      name = l.slice(4).trim().replace(/^b\//, "");
      const base = name.split("/").pop()!;
      const match = [...files.keys()].find((k) => k === name || k.split("/").pop() === base);
      target = match ? files.get(match)!.split("\n") : null;
      if (!target) problems.push(`${name}: not one of the files sent`);
      continue;
    }
    const h = /^@@ -(\d+)(?:,(\d+))? \+\d+(?:,\d+)? @@/.exec(l);
    if (!h || !target) continue;
    let at = Number(h[1]) - 1;
    for (let j = i + 1; j < lines.length && !lines[j]!.startsWith("@@") && !lines[j]!.startsWith("--- "); j++) {
      const hl = lines[j]!;
      if (hl.startsWith("+") || hl === "\\ No newline at end of file") continue;
      if (hl.startsWith(" ") || hl.startsWith("-") || hl === "") {
        const want = hl.slice(1);
        if (target[at] !== want && !(hl === "" && target[at] === "")) {
          problems.push(`${name}: line ${at + 1} doesn't match the hunk`);
          break;
        }
        at++;
      }
    }
  }
  if (!/^\+\+\+ /m.test(diff)) problems.push("no file headers");
  return problems;
}

const CodingInput = z.strictObject({ brief: Brief, task: z.enum(["review", "debug", "patch", "document"]).default("review") });
const Finding = z.object({ file: z.string().max(200), line: z.number().int().nullable().default(null), severity: z.enum(["critical", "high", "medium", "low"]), issue: z.string().max(1000), fix: z.string().max(2000) });
const CodingAnswer = z.object({ findings: z.array(Finding).max(50).default([]), patch: z.string().max(60_000).nullable().default(null), documentation: z.string().max(40_000).nullable().default(null), overview: z.string().max(3000) });
const CodingOutput = z.strictObject({
  findings: z.array(Finding),
  patch: z.strictObject({ fileId: z.string(), applies: z.boolean(), problems: z.array(z.string()) }).nullable(),
  reportFileId: z.string(),
  summary: Summary,
});

export const codingSkill = defineSkill<z.infer<typeof CodingInput>, z.infer<typeof CodingOutput>>({
  id: "coding.engineer",
  version: 1,
  input: CodingInput,
  output: CodingOutput,
  tools: ["files.read", "files.write", "channel.post", "repository.write", "deploy.production"],
  requiredTools: ["files.read", "files.write"],
  categories: ["reasoning"],
  requiredCategories: ["reasoning"],
  files: { min: 1, max: 20, maxBytes: 2 * MB, mimes: CODE_MIMES },
  limits: { maxRuntimeSec: 300, maxModelCalls: 3, maxAttempts: 2, maxOutputFiles: 3 },
  approvals: ["Writing to a repository or deploying needs its own approval and a connection that doesn't exist yet: it delivers a patch file instead."],
  errors: ["No source files: refused.", "A patch that doesn't apply to the files sent is delivered marked as not applying, with the reasons."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    requireInputs(ctx, CODE_MIMES, "the source files or logs to work on");
    const sources = await readTexts(ctx, CODE_MIMES, 150_000);
    const numbered = (s: Source) => s.text.split("\n").map((l, i) => `${String(i + 1).padStart(5)} ${l}`).join("\n");
    const answer = await ctx.models.json("reasoning", CodingAnswer, {
      system: `You're a senior engineer. Task: ${input.task}. Report concrete findings with file names and line numbers from the numbered listings.${input.task === "patch" || input.task === "debug" ? ' Put any change in "patch" as a unified diff (--- a/name, +++ b/name, @@ hunks) against the files exactly as given.' : ""}${input.task === "document" ? ' Put the documentation in "documentation" as Markdown.' : ""} JSON only: {"overview","findings":[{"file","line","severity","issue","fix"}],"patch","documentation"}.`,
      prompt: `${untrusted("request", input.brief)}\n\n${sources.map((s) => untrusted(s.name, numbered(s))).join("\n\n")}`,
      maxOutputTokens: 6000,
    });
    const files: string[] = [];
    let patch: z.infer<typeof CodingOutput>["patch"] = null;
    if (answer.patch?.trim()) {
      const problems = checkPatch(answer.patch, new Map(sources.map((s) => [s.name, s.text])));
      const f = await deliver(ctx, problems.length ? "changes-NOT-APPLYING.diff" : "changes.diff", "text/x-diff", answer.patch);
      files.push(f.id);
      patch = { fileId: f.id, applies: problems.length === 0, problems };
    }
    const report = [
      `# ${input.task[0]!.toUpperCase()}${input.task.slice(1)}`,
      answer.overview,
      answer.findings.length ? `## Findings\n\n${mdTable(["Severity", "File", "Line", "Issue", "Fix"], answer.findings.map((f) => [f.severity, f.file, f.line, f.issue, f.fix]))}` : "No findings.",
      patch ? `## Patch\n\n${patch.applies ? "The patch applies cleanly to the files sent." : `The patch does NOT apply to the files sent:\n${patch.problems.map((p) => `- ${p}`).join("\n")}`}\nPushing it to a repository needs a repository connection, which isn't available.` : "",
      answer.documentation ?? "",
    ]
      .filter(Boolean)
      .join("\n\n");
    const r = await deliver(ctx, `${input.task}-report.md`, "text/markdown", report);
    files.unshift(r.id);
    const crit = answer.findings.filter((f) => f.severity === "critical" || f.severity === "high").length;
    const summary = `${answer.findings.length} findings (${crit} critical or high)${patch ? `; patch ${patch.applies ? "applies cleanly" : "doesn't apply"}` : ""}.`;
    return finish(ctx, { findings: answer.findings, patch, reportFileId: r.id, summary }, summary, files);
  },
});

const QaInput = z.strictObject({ brief: Brief, kind: z.enum(["test_plan", "bug_report"]).default("test_plan") });
const Case = z.object({
  title: z.string().max(200),
  area: z.string().max(80).default("general"),
  preconditions: z.string().max(500).default(""),
  steps: z.array(z.string().min(1).max(300)).min(1).max(20),
  expected: z.string().min(1).max(500),
  priority: z.enum(["P0", "P1", "P2", "P3"]),
});
const Bug = z.object({
  title: z.string().max(200),
  severity: z.enum(["critical", "major", "minor", "trivial"]),
  summary: z.string().max(2000),
  stepsToReproduce: z.array(z.string().max(300)).min(1).max(20),
  expected: z.string().max(500),
  actual: z.string().max(500),
  evidenceLines: z.array(z.number().int()).max(30).default([]),
  suspectedCause: z.string().max(1000).nullable().default(null),
});
const QaOutput = z.strictObject({ kind: z.enum(["test_plan", "bug_report"]), cases: z.number().int(), evidence: z.number().int(), fileIds: z.array(z.string()), summary: Summary });

/** Log lines that look like failures, with their line numbers (1-based). */
export function failureLines(text: string, max = 200): { line: number; text: string }[] {
  const out: { line: number; text: string }[] = [];
  text.split("\n").forEach((l, i) => {
    if (out.length < max && /\b(error|exception|fatal|panic|traceback|failed|failure|assert(ion)?)\b/i.test(l)) out.push({ line: i + 1, text: l.slice(0, 300) });
  });
  return out;
}

export const qaSkill = defineSkill<z.infer<typeof QaInput>, z.infer<typeof QaOutput>>({
  id: "qa.testing",
  version: 1,
  input: QaInput,
  output: QaOutput,
  tools: ["files.read", "files.write", "channel.post"],
  requiredTools: ["files.write"],
  categories: ["reasoning"],
  requiredCategories: ["reasoning"],
  files: { max: 10, maxBytes: 5 * MB, mimes: CODE_MIMES },
  limits: { maxRuntimeSec: 240, maxModelCalls: 3, maxAttempts: 2, maxOutputFiles: 2 },
  approvals: ["None: it writes documents."],
  errors: ["A bug report needs logs or test output; evidence it cites must be real failure lines of those files, others are dropped."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const sources = await readTexts(ctx, CODE_MIMES, 100_000);
    if (input.kind === "test_plan") {
      const plan = await ctx.models.json("reasoning", z.object({ cases: z.array(Case).min(1).max(60) }), {
        system: 'Write a test plan as concrete, reproducible test cases covering normal use, edge cases and failures. JSON only: {"cases":[{"title","area","preconditions","steps":[...],"expected","priority":"P0".."P3"}]}.',
        prompt: `${untrusted("request", input.brief)}${sources.length ? `\n\n${sources.map((s) => untrusted(s.name, s.text)).join("\n\n")}` : ""}`,
        maxOutputTokens: 6000,
      });
      const rows = plan.cases.map((c, i) => [`TC-${String(i + 1).padStart(3, "0")}`, c.priority, c.area, c.title, c.preconditions, c.steps.map((s, k) => `${k + 1}. ${s}`).join(" "), c.expected]);
      const header = ["ID", "Priority", "Area", "Title", "Preconditions", "Steps", "Expected"];
      const csv = await deliver(ctx, "test-plan.csv", "text/csv", toCsv(header, rows));
      const md = await deliver(ctx, "test-plan.md", "text/markdown", `# Test plan\n\n${mdTable(header, rows)}\n`);
      const p0 = plan.cases.filter((c) => c.priority === "P0").length;
      const summary = `${plan.cases.length} test cases (${p0} P0).`;
      return finish(ctx, { kind: input.kind, cases: plan.cases.length, evidence: 0, fileIds: [md.id, csv.id], summary }, summary, [md.id, csv.id]);
    }
    if (!sources.length) throw new ApiError(400, "bad_request", "Send the logs or test output the bug report should be based on.");
    const candidates = sources.flatMap((s) => failureLines(s.text).map((f) => ({ ...f, source: s.name })));
    const listing = candidates.map((c, i) => `#${i + 1} ${c.source}:${c.line} ${c.text}`).join("\n");
    const bug = await ctx.models.json("reasoning", Bug, {
      system: 'Write a reproducible bug report. "evidenceLines" are the # numbers of the failure lines below that support it. JSON only: {"title","severity","summary","stepsToReproduce","expected","actual","evidenceLines","suspectedCause"}.',
      prompt: `${untrusted("request", input.brief)}\n\nFailure lines found in the files:\n${untrusted("logs", listing || "(none found)")}\n\nFull files:\n${sources.map((s) => untrusted(s.name, s.text.slice(0, 30_000))).join("\n\n")}`,
      maxOutputTokens: 3000,
    });
    const evidence = [...new Set(bug.evidenceLines)].filter((n) => n >= 1 && n <= candidates.length).map((n) => candidates[n - 1]!);
    const md = [
      `# ${bug.title}`,
      `**Severity:** ${bug.severity}`,
      bug.summary,
      `## Steps to reproduce\n\n${bug.stepsToReproduce.map((s, i) => `${i + 1}. ${s}`).join("\n")}`,
      `## Expected\n\n${bug.expected}\n\n## Actual\n\n${bug.actual}`,
      `## Evidence\n\n${evidence.length ? evidence.map((e) => `- \`${e.source}:${e.line}\` ${e.text}`).join("\n") : "No failure line in the files supports this directly."}`,
      bug.suspectedCause ? `## Suspected cause\n\n${bug.suspectedCause}` : "",
    ]
      .filter(Boolean)
      .join("\n\n");
    const f = await deliver(ctx, "bug-report.md", "text/markdown", md);
    const summary = `Bug report "${bug.title}" (${bug.severity}), ${evidence.length} log lines as evidence.`;
    return finish(ctx, { kind: input.kind, cases: 0, evidence: evidence.length, fileIds: [f.id], summary }, summary, [f.id]);
  },
});
