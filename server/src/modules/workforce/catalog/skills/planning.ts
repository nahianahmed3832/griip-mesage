import { z } from "zod/v4";
import { untrusted } from "../../prompting.js";
import { defineSkill } from "../../types.js";
import { Brief, deliver, finish, mdTable, MB, readTexts, round, Summary, TEXT_MIMES, toCsv } from "../common.js";

// ───────────── Project Manager ─────────────

const Task = z.object({
  id: z.string().regex(/^[A-Za-z0-9_-]{1,20}$/),
  title: z.string().min(1).max(200),
  estimateHours: z.number().min(0.25).max(400),
  dependsOn: z.array(z.string()).max(20).default([]),
  owner: z.string().max(80).nullable().default(null),
});
type Task = z.infer<typeof Task>;

export interface Scheduled extends Task {
  start: number;
  finish: number;
  slack: number;
  critical: boolean;
}

/**
 * Orders tasks by their dependencies and computes the critical path, in hours. Unknown dependencies and the edges that
 * close a cycle are removed, and listed, rather than trusted.
 */
export function schedule(all: Task[]): { tasks: Scheduled[]; removed: string[]; totalHours: number } {
  const removed: string[] = [];
  const tasks = all.filter((t, i) => all.findIndex((x) => x.id === t.id) === i);
  if (tasks.length < all.length) removed.push(`${all.length - tasks.length} duplicate task ids`);
  const ids = new Set(tasks.map((t) => t.id));
  const deps = new Map<string, string[]>();
  for (const t of tasks) {
    const known = t.dependsOn.filter((d) => ids.has(d) && d !== t.id);
    for (const d of t.dependsOn) if (!known.includes(d)) removed.push(`${t.id} → ${d} (unknown)`);
    deps.set(t.id, known);
  }
  // Remove back-edges found by depth-first search, so what's left is acyclic.
  const state = new Map<string, 0 | 1 | 2>();
  const visit = (id: string) => {
    state.set(id, 1);
    for (const d of [...deps.get(id)!]) {
      if (state.get(d) === 1) {
        deps.set(id, deps.get(id)!.filter((x) => x !== d));
        removed.push(`${id} → ${d} (cycle)`);
      } else if (!state.get(d)) visit(d);
    }
    state.set(id, 2);
  };
  for (const t of tasks) if (!state.get(t.id)) visit(t.id);
  const order: Task[] = [];
  const placed = new Set<string>();
  while (order.length < tasks.length) {
    const next = tasks.find((t) => !placed.has(t.id) && deps.get(t.id)!.every((d) => placed.has(d)));
    if (!next) break;
    order.push(next);
    placed.add(next.id);
  }
  const finish = new Map<string, number>();
  const start = new Map<string, number>();
  for (const t of order) {
    const s = Math.max(0, ...deps.get(t.id)!.map((d) => finish.get(d)!));
    start.set(t.id, s);
    finish.set(t.id, s + t.estimateHours);
  }
  const total = Math.max(0, ...finish.values());
  const lateFinish = new Map<string, number>();
  for (const t of [...order].reverse()) {
    const succ = order.filter((x) => deps.get(x.id)!.includes(t.id));
    lateFinish.set(t.id, succ.length ? Math.min(...succ.map((x) => lateFinish.get(x.id)! - x.estimateHours)) : total);
  }
  return {
    tasks: order.map((t) => {
      const slack = round(lateFinish.get(t.id)! - finish.get(t.id)!, 2);
      return { ...t, dependsOn: deps.get(t.id)!, start: round(start.get(t.id)!, 2), finish: round(finish.get(t.id)!, 2), slack, critical: slack <= 0.001 };
    }),
    removed,
    totalHours: round(total, 2),
  };
}

const ProjectInput = z.strictObject({ brief: Brief, hoursPerDay: z.number().min(1).max(12).default(6) });
const ProjectOutput = z.strictObject({ tasks: z.number().int(), criticalPath: z.array(z.string()), totalHours: z.number(), workingDays: z.number(), removedDependencies: z.array(z.string()), fileIds: z.array(z.string()), summary: Summary });

export const projectSkill = defineSkill<z.infer<typeof ProjectInput>, z.infer<typeof ProjectOutput>>({
  id: "project.plan",
  version: 1,
  input: ProjectInput,
  output: ProjectOutput,
  tools: ["files.read", "files.write", "channel.post"],
  requiredTools: ["files.write"],
  categories: ["reasoning"],
  requiredCategories: ["reasoning"],
  files: { max: 5, maxBytes: 5 * MB, mimes: TEXT_MIMES },
  limits: { maxRuntimeSec: 180, maxModelCalls: 2, maxAttempts: 2, maxOutputFiles: 2 },
  approvals: ["None: it writes plans."],
  errors: ["Dependencies on unknown tasks and dependency cycles are removed and listed in the plan."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const sources = await readTexts(ctx, TEXT_MIMES, 60_000);
    const answer = await ctx.models.json("reasoning", z.object({ tasks: z.array(Task).min(1).max(60) }), {
      system: 'Break the goal into concrete tasks with estimates in hours and dependencies (ids of tasks that must finish first). JSON only: {"tasks":[{"id","title","estimateHours","dependsOn","owner"}]}.',
      prompt: `${untrusted("goal", input.brief)}${sources.length ? `\n\n${sources.map((s) => untrusted(s.name, s.text)).join("\n\n")}` : ""}`,
      maxOutputTokens: 5000,
    });
    const plan = schedule(answer.tasks);
    const day = (h: number) => round(h / input.hoursPerDay, 1);
    const header = ["ID", "Task", "Owner", "Hours", "Depends on", "Start (day)", "Finish (day)", "Slack (h)", "Critical"];
    const rows = plan.tasks.map((t) => [t.id, t.title, t.owner, t.estimateHours, t.dependsOn.join(" "), day(t.start), day(t.finish), t.slack, t.critical ? "yes" : ""]);
    const critical = plan.tasks.filter((t) => t.critical).map((t) => t.id);
    const md = `# Project plan\n\n${plan.tasks.length} tasks, ${plan.totalHours} hours on the critical path (about ${day(plan.totalHours)} working days at ${input.hoursPerDay} h/day).\n\n**Critical path:** ${critical.join(" → ")}\n\n${mdTable(header, rows)}\n${plan.removed.length ? `\n**Dependencies removed:** ${plan.removed.join("; ")}\n` : ""}`;
    const mdFile = await deliver(ctx, "project-plan.md", "text/markdown", md);
    const csvFile = await deliver(ctx, "project-plan.csv", "text/csv", toCsv(header, rows));
    const summary = `${plan.tasks.length} tasks; critical path ${critical.length} tasks, ${day(plan.totalHours)} working days.`;
    return finish(ctx, { tasks: plan.tasks.length, criticalPath: critical, totalHours: plan.totalHours, workingDays: day(plan.totalHours), removedDependencies: plan.removed, fileIds: [mdFile.id, csvFile.id], summary }, summary, [mdFile.id, csvFile.id]);
  },
});

// ───────────── HR and Workplace Operations ─────────────

const DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;
const Time = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/);
export const Roster = z.object({
  people: z.array(z.object({ name: z.string().min(1).max(80), maxHours: z.number().min(0).max(80), unavailable: z.array(z.enum(DAYS)).default([]) })).min(1).max(100),
  shifts: z.array(z.object({ day: z.enum(DAYS), start: Time, end: Time, needed: z.number().int().min(1).max(20) })).min(1).max(200),
});
type Roster = z.infer<typeof Roster>;

const minutes = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3));

/** Assigns people to shifts: available that day, under their hours, not double-booked, fewest hours first. */
export function assignShifts(r: Roster): { assignments: { day: string; start: string; end: string; people: string[]; missing: number }[]; hours: Map<string, number> } {
  const hours = new Map(r.people.map((p) => [p.name, 0]));
  const booked = new Map<string, { day: string; a: number; b: number }[]>(r.people.map((p) => [p.name, []]));
  const sorted = [...r.shifts].sort((x, y) => DAYS.indexOf(x.day) - DAYS.indexOf(y.day) || minutes(x.start) - minutes(y.start));
  const assignments = sorted.map((s) => {
    const a = minutes(s.start);
    const b = minutes(s.end) > a ? minutes(s.end) : minutes(s.end) + 24 * 60;
    const len = (b - a) / 60;
    const people = r.people
      .filter((p) => !p.unavailable.includes(s.day) && hours.get(p.name)! + len <= p.maxHours && !booked.get(p.name)!.some((x) => x.day === s.day && x.a < b && a < x.b))
      .sort((x, y) => hours.get(x.name)! - hours.get(y.name)! || x.name.localeCompare(y.name))
      .slice(0, s.needed)
      .map((p) => p.name);
    for (const n of people) {
      hours.set(n, hours.get(n)! + len);
      booked.get(n)!.push({ day: s.day, a, b });
    }
    return { day: s.day, start: s.start, end: s.end, people, missing: s.needed - people.length };
  });
  return { assignments, hours };
}

const HrInput = z.strictObject({ brief: Brief, task: z.enum(["onboarding_plan", "shift_schedule", "policy_document"]).default("onboarding_plan"), roster: Roster.nullable().default(null) });
const Doc = z.object({
  title: z.string().max(150),
  sections: z.array(z.object({ heading: z.string().max(150), body: z.string().max(6000) })).min(1).max(20),
  checklist: z.array(z.object({ when: z.string().max(60), item: z.string().max(300), owner: z.string().max(80) })).max(80).default([]),
});
const HrOutput = z.strictObject({ task: HrInput.shape.task, gaps: z.number().int(), fileIds: z.array(z.string()), summary: Summary });
const SENSITIVE =
  "Never infer, ask for or record health, religion, ethnicity, sexuality, union membership, immigration status or other sensitive personal characteristics, and never recommend decisions about an individual's employment.";

export const hrSkill = defineSkill<z.infer<typeof HrInput>, z.infer<typeof HrOutput>>({
  id: "hr.ops",
  version: 1,
  input: HrInput,
  output: HrOutput,
  tools: ["files.read", "files.write", "channel.post"],
  requiredTools: ["files.write"],
  categories: ["reasoning"],
  requiredCategories: ["reasoning"],
  files: { max: 5, maxBytes: 5 * MB, mimes: TEXT_MIMES },
  limits: { maxRuntimeSec: 180, maxModelCalls: 2, maxAttempts: 2, maxOutputFiles: 3 },
  approvals: ["None: it writes documents and schedules for people to use."],
  errors: ["A shift schedule needs people and shifts (given, or found in the brief); shifts that can't be covered are listed as gaps, never filled with someone unavailable."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const sources = await readTexts(ctx, TEXT_MIMES, 60_000);
    const material = `${untrusted("request", input.brief)}${sources.length ? `\n\n${sources.map((s) => untrusted(s.name, s.text)).join("\n\n")}` : ""}`;
    if (input.task === "shift_schedule") {
      const roster =
        input.roster ??
        (await ctx.models.json("reasoning", Roster, {
          system: `Extract the people (name, maxHours per week, unavailable weekdays as mon..sun) and the shifts (day mon..sun, start and end HH:MM, needed) from the material. Don't invent people or shifts. ${SENSITIVE} JSON only: {"people":[...],"shifts":[...]}.`,
          prompt: material,
          maxOutputTokens: 3000,
        }));
      const { assignments, hours } = assignShifts(roster);
      const header = ["Day", "Start", "End", "People", "Missing"];
      const rows = assignments.map((a) => [a.day, a.start, a.end, a.people.join("; "), a.missing || ""]);
      const gaps = assignments.filter((a) => a.missing > 0);
      const csv = await deliver(ctx, "shift-schedule.csv", "text/csv", toCsv(header, rows));
      const md = await deliver(
        ctx,
        "shift-schedule.md",
        "text/markdown",
        `# Shift schedule\n\n${mdTable(header, rows)}\n\n## Hours per person\n\n${mdTable(["Person", "Hours", "Max"], roster.people.map((p) => [p.name, round(hours.get(p.name)!, 2), p.maxHours]))}\n\n${gaps.length ? `**${gaps.length} shifts can't be fully covered** with the availability given.` : "Every shift is covered."}\n`,
      );
      const summary = `${assignments.length} shifts scheduled for ${roster.people.length} people; ${gaps.length} not fully covered.`;
      return finish(ctx, { task: input.task, gaps: gaps.length, fileIds: [md.id, csv.id], summary }, summary, [md.id, csv.id]);
    }
    const doc = await ctx.models.json("reasoning", Doc, {
      system: `Write a ${input.task === "onboarding_plan" ? "practical onboarding plan with a checklist by week" : "clear workplace policy document"}. ${SENSITIVE} JSON only: {"title","sections":[{"heading","body"}],"checklist":[{"when","item","owner"}]}.`,
      prompt: material,
      maxOutputTokens: 5000,
    });
    const md = await deliver(ctx, `${input.task.replace("_", "-")}.md`, "text/markdown", `# ${doc.title}\n\n${doc.sections.map((s) => `## ${s.heading}\n\n${s.body}`).join("\n\n")}${doc.checklist.length ? `\n\n## Checklist\n\n${mdTable(["When", "Item", "Owner"], doc.checklist.map((c) => [c.when, c.item, c.owner]))}` : ""}\n`);
    const files = [md.id];
    if (doc.checklist.length) files.push((await deliver(ctx, "checklist.csv", "text/csv", toCsv(["When", "Item", "Owner"], doc.checklist.map((c) => [c.when, c.item, c.owner])))).id);
    const summary = `"${doc.title}": ${doc.sections.length} sections${doc.checklist.length ? `, ${doc.checklist.length} checklist items` : ""}.`;
    return finish(ctx, { task: input.task, gaps: 0, fileIds: files, summary }, summary, files);
  },
});
