import { z } from "zod/v4";
import { ApiError } from "../../../../shared/errors.js";
import { untrusted } from "../../prompting.js";
import { defineSkill, type SkillContext, type SkillOutcome } from "../../types.js";
import { Brief, deliver, finish, MB, post, sharedSource, Summary, TEXT_MIMES, VIDEO_MIMES, AUDIO_MIMES, IMAGE_MIMES } from "../common.js";

/**
 * Agent Manager: understands a request, and either answers it, asks one question, or plans steps for the community's
 * own agents and delegates them through the tool gateway (as the person who asked, never with more). It reports only
 * what the delegated jobs' records say: their states, their validated outputs and the files they stored.
 */
const Input = z.strictObject({ brief: Brief });
type Input = z.infer<typeof Input>;

export const MAX_STEPS = 6;

const Step = z.object({
  key: z.string().regex(/^[a-z0-9_-]{1,20}$/),
  installId: z.string(),
  task: z.string().min(1).max(4000),
  dependsOn: z.array(z.string()).max(MAX_STEPS).default([]),
});
const Plan = z.object({
  mode: z.enum(["answer", "ask", "delegate"]),
  answer: z.string().max(8000).nullable().default(null),
  question: z.string().max(1000).nullable().default(null),
  steps: z.array(Step).max(MAX_STEPS).default([]),
});
type Plan = z.infer<typeof Plan>;

const StepResult = z.strictObject({ label: z.string(), agentId: z.string(), jobId: z.string(), state: z.string(), fileIds: z.array(z.string()), error: z.string().nullable() });
const Output = z.strictObject({
  mode: z.enum(["answer", "delegate"]),
  steps: z.array(StepResult),
  answer: z.string(),
  reportFileId: z.string().nullable(),
  summary: Summary,
});
type Output = z.infer<typeof Output>;

const Checkpoint = z.object({
  phase: z.literal("waiting"),
  steps: z.array(z.object({ key: z.string(), jobId: z.string(), label: z.string(), agentName: z.string() })),
});
const AskCheckpoint = z.object({ phase: z.literal("asked"), question: z.string(), answer: z.string().optional() });

/**
 * Checks a plan against the team: known agents only, at most MAX_STEPS, keys unique, and every dependency on an
 * earlier step, so the steps can't form a cycle. Returns the steps in order, or why it's refused.
 */
export function checkPlan(plan: Plan, team: ReadonlySet<string>): { ok: true; steps: Plan["steps"] } | { ok: false; why: string } {
  if (plan.mode !== "delegate") return { ok: true, steps: [] };
  if (plan.steps.length === 0) return { ok: false, why: "no steps" };
  const seen = new Set<string>();
  for (const s of plan.steps) {
    if (!team.has(s.installId)) return { ok: false, why: `${s.installId} isn't an agent you can use here` };
    if (seen.has(s.key)) return { ok: false, why: `step ${s.key} appears twice` };
    for (const d of s.dependsOn) if (!seen.has(d)) return { ok: false, why: `step ${s.key} depends on ${d}, which isn't an earlier step` };
    seen.add(s.key);
  }
  return { ok: true, steps: plan.steps };
}

async function plan(ctx: SkillContext, input: Input, answer: string | null): Promise<SkillOutcome<Output>> {
  const team = ctx.team();
  ctx.progress(10, "Understanding the request");
  const roster = team.map((t) => `- installId ${t.installId}: ${t.name} (${t.roleTitle}). ${t.description} Can: ${t.capabilities.slice(0, 5).join("; ")}. Request: ${t.inputHint}`).join("\n");
  const files = ctx.inputs.map((f) => `${f.name} (${f.mime}, ${(f.size / MB).toFixed(1)} MB)`).join(", ");
  // Messages shared from the channel: the coordinator may answer from them, and every step gets them too.
  const shared = sharedSource(ctx, 30_000);
  const p = await ctx.models.json("reasoning", Plan, {
    system: [
      "You coordinate a team of AI agents. Use the smallest workflow that does the job:",
      '- "answer": you can answer yourself (questions, short advice). Put it in "answer".',
      '- "ask": something essential is missing. Put one question in "question".',
      '- "delegate": specialists are needed. Give "steps": [{"key","installId","task","dependsOn":[earlier keys]}]. Use only the listed installIds; each task is a complete, self-contained request for that agent; the files and channel messages sent with the request are available to every step.',
      `At most ${MAX_STEPS} steps. Never invent agents or capabilities. Answer with JSON only.`,
    ].join("\n"),
    prompt: `Team:\n${roster || "(no other agents you can use)"}\n\nFiles sent with the request: ${files || "none"}${shared ? `\n\nChannel messages shared with the request:\n${untrusted(shared.name, shared.text)}` : ""}\n\nRequest:\n${untrusted("request", input.brief)}${answer ? `\n\nThe person's answer to your question:\n${untrusted("answer", answer)}` : ""}`,
    maxOutputTokens: 2000,
  });
  if (p.mode === "ask" && p.question && !answer) {
    await post(ctx, "progress", p.question);
    return { kind: "await_input", question: p.question, checkpoint: { phase: "asked", question: p.question } };
  }
  if (p.mode !== "delegate") {
    const text = p.answer?.trim() || "I can't help with this using the agents available here.";
    const output: Output = { mode: "answer", steps: [], answer: text, reportFileId: null, summary: text.slice(0, 300) };
    return finish(ctx, output, output.summary, [], text);
  }
  const checked = checkPlan(p, new Set(team.map((t) => t.installId)));
  if (!checked.ok) throw new ApiError(502, "provider_error", `The plan couldn't be used (${checked.why}).`);
  ctx.progress(25, "Handing out the work");
  const byKey = new Map<string, string>();
  const steps: z.infer<typeof Checkpoint>["steps"] = [];
  for (const s of checked.steps) {
    const member = team.find((t) => t.installId === s.installId)!;
    const label = `${member.name}: ${s.task.slice(0, 60)}`;
    const { jobId } = await ctx.tool("agents.delegate", { installId: s.installId, input: { brief: s.task }, dependsOn: s.dependsOn.map((d) => byKey.get(d)!), label });
    byKey.set(s.key, jobId);
    steps.push({ key: s.key, jobId, label, agentName: member.name });
    ctx.event("delegated_step", { key: s.key, jobId, agent: member.agentId });
  }
  await post(ctx, "progress", `I've split this into ${steps.length} step${steps.length === 1 ? "" : "s"}:\n${steps.map((s, i) => `${i + 1}. ${s.label}`).join("\n")}`);
  return { kind: "wait_children", jobIds: steps.map((s) => s.jobId), checkpoint: { phase: "waiting", steps } };
}

async function report(ctx: SkillContext, cp: z.infer<typeof Checkpoint>): Promise<SkillOutcome<Output>> {
  ctx.progress(85, "Collecting the results");
  const records = new Map(ctx.children().map((c) => [c.jobId, c]));
  const steps = cp.steps.map((s) => {
    const r = records.get(s.jobId);
    return { label: s.label, agentId: r?.agentId ?? "", jobId: s.jobId, state: r?.state ?? "missing", fileIds: r?.state === "completed" ? r.fileIds : [], error: r?.error ?? (r ? null : "no record of this step") };
  });
  const done = steps.filter((s) => s.state === "completed");
  // The summary is written from the steps' validated outputs; which steps ran and which files exist comes from records.
  const material = cp.steps
    .map((s) => {
      const r = records.get(s.jobId);
      const out = r?.state === "completed" ? JSON.stringify(r.output).slice(0, 6000) : `did not complete (${r?.error ?? r?.state ?? "missing"})`;
      return `Step "${s.label}":\n${untrusted(s.agentName, out)}`;
    })
    .join("\n\n");
  let answer = "";
  if (done.length && ctx.models.available("fast")) {
    answer = await ctx.models.text("fast", {
      system: "Summarise for the person what the steps produced, in a few short paragraphs. Mention failures plainly. Don't claim anything the material doesn't show. Don't list files; they're listed separately.",
      prompt: material,
      maxOutputTokens: 800,
    });
  }
  const lines = steps.map((s) => `- ${s.state === "completed" ? "Done" : s.state === "failed" ? "Failed" : s.state === "cancelled" ? "Cancelled" : s.state}: ${s.label}${s.error ? ` (${s.error})` : ""}${s.fileIds.length ? ` · ${s.fileIds.length} file${s.fileIds.length === 1 ? "" : "s"}` : ""}`);
  const markdown = `# Request report\n\n${answer ? `${answer}\n\n` : ""}## Steps\n\n${lines.join("\n")}\n`;
  const file = await deliver(ctx, "report.md", "text/markdown", markdown);
  const fileIds = [...done.flatMap((s) => s.fileIds), file.id];
  const summary = `${done.length} of ${steps.length} steps completed${steps.length > done.length ? `; ${steps.length - done.length} didn't` : ""}.`;
  const output: Output = { mode: "delegate", steps, answer: answer || summary, reportFileId: file.id, summary };
  return finish(ctx, output, summary, fileIds, `${summary}\n${lines.join("\n")}${answer ? `\n\n${answer}` : ""}`);
}

export const managerSkill = defineSkill<Input, Output>({
  id: "manager.orchestrate",
  version: 1,
  input: Input,
  output: Output,
  tools: ["files.read", "files.write", "channel.post", "agents.delegate"],
  requiredTools: ["agents.delegate", "files.write"],
  categories: ["reasoning", "fast"],
  requiredCategories: ["reasoning"],
  files: { max: 10, maxBytes: 500 * MB, mimes: [...TEXT_MIMES, ...VIDEO_MIMES, ...AUDIO_MIMES, ...IMAGE_MIMES] },
  limits: { maxRuntimeSec: 300, maxModelCalls: 6, maxAttempts: 2, maxOutputFiles: 2 },
  approvals: ["It can't approve anything: approvals its steps need go to people with ai.approve."],
  errors: [
    "A plan naming an agent the person can't use, a forward dependency or too many steps is refused (the job retries once, then fails).",
    "A step that fails is reported as failed; the others still report.",
    "Delegation is one level deep: delegated agents can't delegate, so requests can't loop.",
  ],
  tests: ["ai_workforce_manager.test.ts"],
  async run(ctx, input) {
    const waiting = Checkpoint.safeParse(ctx.checkpoint);
    if (waiting.success) return report(ctx, waiting.data);
    const asked = AskCheckpoint.safeParse(ctx.checkpoint);
    return plan(ctx, input, asked.success ? (asked.data.answer ?? null) : null);
  },
});
