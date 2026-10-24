import { z } from "zod/v4";
import { ApiError } from "../../../../shared/errors.js";
import { untrusted } from "../../prompting.js";
import { defineSkill, type EditPlan, type MediaInfo, type SkillContext, type SkillOutcome } from "../../types.js";
import { toSrt } from "../../media.js";
import { Brief, deliver, finish, MB, post, requireInputs, round, Summary, VIDEO_MIMES } from "../common.js";

/**
 * Product Video Editor: footage in, a cut video out. The media engine does the cutting (media.ts); the model only
 * proposes which parts to keep, and that proposal is checked against the files' real durations. Transcription, when a
 * speech-to-text model is configured, gives captions with the provider's own timings. In review mode the final export
 * waits for a person to approve the exact preview they saw; a revision request makes a new preview.
 */
const Input = z.strictObject({
  brief: Brief,
  targetSeconds: z.number().int().min(5).max(300).default(30),
  captions: z.boolean().default(true),
  title: z.string().trim().max(80).nullable().default(null),
  brandText: z.string().trim().max(60).nullable().default(null),
});
type Input = z.infer<typeof Input>;

const Clip = z.strictObject({ fileId: z.string(), startSec: z.number().min(0), endSec: z.number().min(0), reason: z.string().max(300) });

const Output = z.strictObject({
  previewFileId: z.string(),
  finalFileId: z.string().nullable(),
  captionsFileId: z.string().nullable(),
  planFileId: z.string(),
  durationSec: z.number(),
  clips: z.array(Clip),
  captions: z.enum(["burned", "track", "none"]),
  transcription: z.enum(["provider", "model", "none", "unavailable", "not_requested"]),
  notes: z.array(z.string()),
  summary: Summary,
});
type Output = z.infer<typeof Output>;

const PlanAnswer = z.object({
  clips: z.array(z.object({ fileId: z.string(), startSec: z.number(), endSec: z.number(), reason: z.string().max(300).default("") })).min(1).max(20),
  title: z.string().max(80).nullable().default(null),
});

const Checkpoint = z.object({
  phase: z.literal("approve"),
  revision: z.number(),
  plan: z.object({
    clips: z.array(z.object({ fileId: z.string(), startSec: z.number(), endSec: z.number() })),
    captions: z.array(z.object({ start: z.number(), end: z.number(), text: z.string() })),
    title: z.string().nullable(),
    brandText: z.string().nullable(),
  }),
  reasons: z.array(z.string()),
  previewId: z.string(),
  approvalId: z.string(),
  captionsFileId: z.string().nullable(),
  planFileId: z.string(),
  durationSec: z.number(),
  captions: z.enum(["burned", "track", "none"]),
  transcription: Output.shape.transcription,
  notes: z.array(z.string()),
});
type Checkpoint = z.infer<typeof Checkpoint>;

type Segments = Map<string, { start: number; end: number; text: string }[]>;

/** Keeps a model's clips inside their files, drops slivers, and stops at about 1.5× the target length. */
export function normaliseClips(clips: z.infer<typeof PlanAnswer>["clips"], infos: Map<string, MediaInfo>, targetSec: number) {
  const out: z.infer<typeof Clip>[] = [];
  let total = 0;
  for (const c of clips) {
    const info = infos.get(c.fileId);
    if (!info?.hasVideo) continue;
    const start = Math.max(0, Math.min(c.startSec, info.durationSec));
    let end = Math.max(0, Math.min(c.endSec, info.durationSec));
    if (end - start < 0.5) continue;
    if (total + (end - start) > targetSec * 1.5) end = start + Math.max(0, targetSec * 1.5 - total);
    if (end - start < 0.5) break;
    out.push({ fileId: c.fileId, startSec: round(start, 3), endSec: round(end, 3), reason: c.reason });
    total += end - start;
  }
  return out;
}

/** When no usable plan comes back: evenly spaced pieces of the footage, filling the target length. */
export function fallbackClips(infos: Map<string, MediaInfo>, targetSec: number) {
  const files = [...infos.entries()].filter(([, i]) => i.hasVideo);
  const per = Math.max(1, targetSec / Math.max(1, files.length));
  const out: z.infer<typeof Clip>[] = [];
  for (const [fileId, info] of files) {
    const pieces = Math.max(1, Math.min(4, Math.round(per / 4)));
    const len = Math.min(info.durationSec, per) / pieces;
    for (let k = 0; k < pieces; k++) {
      const start = ((info.durationSec - len) * (k + 0.5)) / pieces;
      if (len >= 0.5) out.push({ fileId, startSec: round(Math.max(0, start), 3), endSec: round(Math.min(info.durationSec, start + len), 3), reason: "evenly spaced (fallback)" });
    }
  }
  return out;
}

/** Transcript segments of each clip's source, moved onto the output's timeline. */
export function mapCaptions(clips: { fileId: string; startSec: number; endSec: number }[], segments: Segments) {
  const out: { start: number; end: number; text: string }[] = [];
  let offset = 0;
  for (const c of clips) {
    for (const s of segments.get(c.fileId) ?? []) {
      const a = Math.max(s.start, c.startSec);
      const b = Math.min(s.end, c.endSec);
      if (b - a >= 0.3 && s.text) out.push({ start: round(offset + a - c.startSec, 3), end: round(offset + b - c.startSec, 3), text: s.text.slice(0, 200) });
    }
    offset += c.endSec - c.startSec;
  }
  return out;
}

async function planPhase(ctx: SkillContext, input: Input, previous: Checkpoint | null): Promise<SkillOutcome<Output>> {
  const videos = requireInputs(ctx, VIDEO_MIMES, "at least one video (MP4, MOV or WebM)");
  const notes: string[] = [];
  ctx.progress(5, "Inspecting the footage");
  const infos = new Map<string, MediaInfo>();
  for (const v of videos) infos.set(v.id, await ctx.tool("media.probe", { fileId: v.id }));
  if (![...infos.values()].some((i) => i.hasVideo)) throw new ApiError(400, "bad_request", "None of the files has a picture.");

  const segments: Segments = new Map();
  let transcription: Output["transcription"] = input.captions ? "none" : "not_requested";
  if (input.captions) {
    if (!ctx.models.available("transcription")) {
      transcription = "unavailable";
      notes.push("No speech-to-text model is configured, so the video has no captions.");
    } else {
      ctx.progress(15, "Transcribing speech");
      for (const v of videos) {
        if (!infos.get(v.id)?.hasAudio) continue;
        const audio = await ctx.tool("media.extract_audio", { fileId: v.id });
        const t = await ctx.models.transcribe({ bytes: audio.bytes, mime: audio.mime, name: `${v.id}.mp3`, seconds: audio.seconds });
        segments.set(v.id, t.segments);
        if (t.timing === "provider" || transcription === "none") transcription = t.timing;
      }
      if (transcription === "model") notes.push("Caption timings are the language model's estimate, not measured by a speech model.");
    }
  }

  ctx.progress(30, "Choosing the scenes");
  const listing = videos
    .map((v) => {
      const i = infos.get(v.id)!;
      const words = (segments.get(v.id) ?? []).map((s) => `[${s.start.toFixed(1)}–${s.end.toFixed(1)}] ${s.text}`).join("\n").slice(0, 6000);
      return `File ${v.id} "${v.name}": ${i.durationSec.toFixed(1)} s, ${i.hasVideo ? `${i.width}×${i.height} video` : "no video"}, ${i.hasAudio ? "sound" : "no sound"}.${words ? `\nWhat's said:\n${untrusted(v.name, words)}` : ""}`;
    })
    .join("\n\n");
  const revision = ctx.job.revisionNote && previous ? `\n\nThe person reviewed the previous version and asked for changes:\n${untrusted("revision request", ctx.job.revisionNote)}\nThe previous cut was: ${JSON.stringify(previous.plan.clips)}` : "";
  let answer: z.infer<typeof PlanAnswer> | null = null;
  try {
    answer = await ctx.models.json("reasoning", PlanAnswer, {
      system: 'You plan product video edits. Choose the strongest moments for the brief. Answer with JSON only: {"clips":[{"fileId","startSec","endSec","reason"}],"title":string|null}. Times are seconds inside each file; clips play in the order given.',
      prompt: `Brief:\n${untrusted("brief", input.brief)}\n\nTarget length: about ${input.targetSeconds} seconds.\n\nFootage:\n${listing}${revision}`,
      maxOutputTokens: 1500,
    });
  } catch (e) {
    if (e instanceof ApiError && (e.code === "ai_not_configured" || e.code === "ai_disabled")) throw e;
    notes.push("The model's scene choice couldn't be used, so the cut takes evenly spaced pieces of the footage.");
  }
  let clips = answer ? normaliseClips(answer.clips, infos, input.targetSeconds) : [];
  if (answer && clips.length === 0) notes.push("The model's scene choice was outside the footage, so the cut takes evenly spaced pieces instead.");
  if (clips.length === 0) clips = fallbackClips(infos, input.targetSeconds);
  const captions = mapCaptions(clips, segments);
  const plan: EditPlan = { clips: clips.map(({ fileId, startSec, endSec }) => ({ fileId, startSec, endSec })), captions, title: input.title ?? answer?.title ?? null, brandText: input.brandText };

  ctx.progress(50, "Rendering the preview");
  const version = ctx.job.revision + 1;
  const preview = await ctx.tool("media.render", { plan, quality: "preview", name: `preview-v${version}.mp4`, supersedes: previous?.previewId ?? null });
  if (preview.captions === "track") notes.push("Captions are a subtitle track (this server's media engine can't burn them into the picture). An .srt file is included.");
  if ((plan.title || plan.brandText) && !preview.overlay) notes.push("The title and brand text are in the file's metadata only: this server's media engine has no text overlay.");
  const planFile = await deliver(ctx, `edit-plan-v${version}.json`, "application/json", JSON.stringify({ ...plan, reasons: clips.map((c) => c.reason) }, null, 2));
  const srt = captions.length ? await deliver(ctx, `captions-v${version}.srt`, "application/x-subrip", toSrt(captions)) : null;

  const cp: Checkpoint = {
    phase: "approve",
    revision: ctx.job.revision,
    plan,
    reasons: clips.map((c) => c.reason),
    previewId: preview.id,
    approvalId: "",
    captionsFileId: srt?.id ?? null,
    planFileId: planFile.id,
    durationSec: round(preview.durationSec, 2),
    captions: captions.length ? preview.captions : "none",
    transcription,
    notes,
  };
  const describe = `${clips.length} scene${clips.length === 1 ? "" : "s"}, ${cp.durationSec} s${captions.length ? `, ${captions.length} captions` : ""}`;
  if (ctx.agent.mode === "auto") return finalPhase(ctx, cp);
  const approvalId = ctx.requestApproval({ action: "media.render", summary: `Export the final video from preview v${version} (${describe}).`, fileId: preview.id });
  ctx.progress(70, "Waiting for approval of the preview");
  await post(ctx, "approval", `Preview v${version} is ready (${describe}). Approve it for the final export, or ask for changes.${notes.length ? `\n\n${notes.join("\n")}` : ""}`, [preview.id, ...(srt ? [srt.id] : [])], approvalId);
  return { kind: "await_approval", approvalId, checkpoint: { ...cp, approvalId }, summary: `Preview v${version} waiting for approval` };
}

async function finalPhase(ctx: SkillContext, cp: Checkpoint): Promise<SkillOutcome<Output>> {
  ctx.progress(80, "Rendering the final video");
  const final = await ctx.tool("media.render", { plan: cp.plan, quality: "final", name: "final.mp4", approvalId: cp.approvalId || null, previewFileId: cp.previewId });
  const output: Output = {
    previewFileId: cp.previewId,
    finalFileId: final.id,
    captionsFileId: cp.captionsFileId,
    planFileId: cp.planFileId,
    durationSec: round(final.durationSec, 2),
    clips: cp.plan.clips.map((c, i) => ({ ...c, reason: cp.reasons[i] ?? "" })),
    captions: cp.captions,
    transcription: cp.transcription,
    notes: cp.notes,
    summary: `Final video: ${cp.plan.clips.length} scenes, ${round(final.durationSec, 1)} s, ${(final.size / MB).toFixed(1)} MB.`,
  };
  const files = [final.id, ...(cp.captionsFileId ? [cp.captionsFileId] : [])];
  return finish(ctx, output, output.summary, files, `${output.summary} Publishing it anywhere outside Meldiq isn't part of this approval.${cp.notes.length ? `\n\n${cp.notes.join("\n")}` : ""}`);
}

export const videoEditSkill = defineSkill<Input, Output>({
  id: "video.edit",
  version: 1,
  input: Input,
  output: Output,
  tools: ["files.read", "files.write", "channel.post", "media.probe", "media.extract_audio", "media.render", "social.publish"],
  requiredTools: ["files.write", "media.probe", "media.render"],
  categories: ["reasoning", "transcription"],
  requiredCategories: ["reasoning"],
  files: { min: 1, max: 10, maxBytes: 500 * MB, mimes: VIDEO_MIMES },
  limits: { maxRuntimeSec: 1800, maxModelCalls: 16, maxAttempts: 2, maxOutputFiles: 40 },
  approvals: ["In review mode the final export waits for approval of the exact preview (bound to its SHA-256); a revision makes a new preview and voids the old approval.", "Publishing outside Meldiq (social.publish) always needs its own approval and a connection that doesn't exist yet."],
  errors: [
    "No video file: refused before the job starts.",
    "No media engine on the server: the job fails with media_engine_unavailable.",
    "No speech-to-text model: the video is made without captions and says so.",
    "A scene plan outside the footage: replaced by evenly spaced pieces, and the job says so.",
  ],
  tests: ["ai_workforce_acceptance.test.ts", "ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const cp = Checkpoint.safeParse(ctx.checkpoint);
    if (cp.success && cp.data.revision === ctx.job.revision) return finalPhase(ctx, cp.data);
    return planPhase(ctx, input, cp.success ? cp.data : null);
  },
});
