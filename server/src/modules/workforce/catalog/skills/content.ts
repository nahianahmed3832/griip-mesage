import { z } from "zod/v4";
import { ApiError } from "../../../../shared/errors.js";
import { sniffMatches } from "../../files.js";
import { untrusted } from "../../prompting.js";
import { defineSkill } from "../../types.js";
import { Brief, deliver, finish, MB, readTexts, Summary, TEXT_MIMES } from "../common.js";

// ───────────── Content Writing ─────────────

const FORMATS = ["announcement", "article", "product_description", "email", "documentation", "marketing_copy"] as const;
export const LENGTHS = { short: [50, 220], medium: [200, 650], long: [600, 1600] } as const;
export const words = (s: string) => (s.match(/[\p{L}\p{N}][\p{L}\p{N}'’-]*/gu) ?? []).length;

const ContentInput = z.strictObject({
  brief: Brief,
  format: z.enum(FORMATS).default("article"),
  length: z.enum(["short", "medium", "long"]).default("medium"),
  tone: z.string().trim().max(60).default("clear and friendly"),
  audience: z.string().trim().max(200).nullable().default(null),
});
const Piece = z.object({ title: z.string().max(200), body: z.string().min(1).max(20_000), headlines: z.array(z.string().max(200)).max(5).default([]) });
const ContentOutput = z.strictObject({ title: z.string(), words: z.number().int(), withinLength: z.boolean(), fileId: z.string(), summary: Summary });

export const contentSkill = defineSkill<z.infer<typeof ContentInput>, z.infer<typeof ContentOutput>>({
  id: "content.write",
  version: 1,
  input: ContentInput,
  output: ContentOutput,
  tools: ["files.read", "files.write", "channel.post"],
  requiredTools: ["files.write"],
  categories: ["reasoning"],
  requiredCategories: ["reasoning"],
  files: { max: 5, maxBytes: 5 * MB, mimes: TEXT_MIMES },
  limits: { maxRuntimeSec: 180, maxModelCalls: 3, maxAttempts: 2, maxOutputFiles: 1 },
  approvals: ["None: it writes drafts; publishing them is a person's step."],
  errors: ["A draft outside the requested length is rewritten once; if it's still outside, it's delivered and marked as such."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const sources = await readTexts(ctx, TEXT_MIMES, 60_000);
    const [min, max] = LENGTHS[input.length];
    const system = `Write a ${input.format.replace("_", " ")} in a ${input.tone} tone${input.audience ? ` for ${input.audience}` : ""}. The body is Markdown of ${min}–${max} words. Use only facts from the brief and the material; don't invent figures or quotes. JSON only: {"title","body","headlines":[alternative titles]}.`;
    const prompt = `${untrusted("brief", input.brief)}${sources.length ? `\n\nMaterial:\n${sources.map((s) => untrusted(s.name, s.text)).join("\n\n")}` : ""}`;
    let piece = await ctx.models.json("reasoning", Piece, { system, prompt, maxOutputTokens: 4000 });
    let n = words(piece.body);
    if (n < min || n > max) {
      piece = await ctx.models.json("reasoning", Piece, { system, prompt: `${prompt}\n\nYour draft had ${n} words; it must have ${min}–${max}. Rewrite it to fit.\n${untrusted("draft", piece.body)}`, maxOutputTokens: 4000 });
      n = words(piece.body);
    }
    const within = n >= min && n <= max;
    const md = `# ${piece.title}\n\n${piece.body}\n${piece.headlines.length ? `\n---\nAlternative titles:\n${piece.headlines.map((h) => `- ${h}`).join("\n")}\n` : ""}`;
    const f = await deliver(ctx, `${input.format.replace("_", "-")}.md`, "text/markdown", md);
    const summary = `${input.format.replace("_", " ")} "${piece.title}", ${n} words${within ? "" : ` (asked for ${min}–${max})`}.`;
    return finish(ctx, { title: piece.title, words: n, withinLength: within, fileId: f.id, summary }, summary, [f.id], `${summary}\n\n${piece.body.slice(0, 1500)}`);
  },
});

// ───────────── Graphic and Image Design ─────────────

const DesignInput = z.strictObject({
  brief: Brief,
  size: z.enum(["1024x1024", "1536x1024", "1024x1536"]).default("1024x1024"),
  count: z.number().int().min(1).max(3).default(1),
});
const DesignPlan = z.object({
  concept: z.string().max(2000),
  palette: z.array(z.string().regex(/^#[0-9A-Fa-f]{6}$/)).max(8).default([]),
  typography: z.string().max(300).default(""),
  images: z.array(z.object({ prompt: z.string().min(1).max(1500), alt: z.string().max(300) })).min(1).max(3),
});
const DesignOutput = z.strictObject({ images: z.array(z.strictObject({ fileId: z.string(), alt: z.string() })), briefFileId: z.string(), generated: z.boolean(), summary: Summary });

export const designSkill = defineSkill<z.infer<typeof DesignInput>, z.infer<typeof DesignOutput>>({
  id: "design.image",
  version: 1,
  input: DesignInput,
  output: DesignOutput,
  tools: ["files.read", "files.write", "channel.post"],
  requiredTools: ["files.write"],
  categories: ["fast", "vision", "image_generation"],
  requiredCategories: ["fast"],
  files: { max: 4, maxBytes: 5 * MB, mimes: ["image/png", "image/jpeg", "image/webp", ...TEXT_MIMES] },
  limits: { maxRuntimeSec: 300, maxModelCalls: 6, maxAttempts: 2, maxOutputFiles: 4 },
  approvals: ["None: images are delivered for people to use."],
  errors: ["No image-generation model configured: it delivers the design brief and image prompts only, and says so.", "A generated file that isn't a PNG, JPEG or WebP image is discarded."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const refs = ctx.inputs.filter((f) => f.mime.startsWith("image/"));
    let referenceNotes = "";
    if (refs.length && ctx.models.available("vision")) {
      const images: { mime: "image/png" | "image/jpeg" | "image/webp" | "image/gif"; base64: string }[] = [];
      for (const r of refs.slice(0, 4)) {
        const f = await ctx.tool("files.read", { fileId: r.id });
        if (f.mime === "image/png" || f.mime === "image/jpeg" || f.mime === "image/webp" || f.mime === "image/gif") images.push({ mime: f.mime, base64: f.bytes.toString("base64") });
      }
      referenceNotes = await ctx.models.vision({ prompt: "Describe the visual style of these reference images: colours, composition, typography, mood. Short bullet points.", images, maxOutputTokens: 500 });
    }
    const plan = await ctx.models.json("fast", DesignPlan, {
      system: `Turn the brief into a design concept and ${input.count} image-generation prompt(s), each self-contained and specific (subject, composition, style, colours; no text in the image unless asked). JSON only: {"concept","palette":["#RRGGBB"],"typography","images":[{"prompt","alt"}]}.`,
      prompt: `${untrusted("brief", input.brief)}${referenceNotes ? `\n\nReference style:\n${untrusted("references", referenceNotes)}` : ""}`,
      maxOutputTokens: 1500,
    });
    const images: { fileId: string; alt: string }[] = [];
    const generate = ctx.models.available("image_generation");
    if (generate) {
      for (const [i, img] of plan.images.slice(0, input.count).entries()) {
        ctx.progress(30 + i * 20, `Generating image ${i + 1}`);
        const out = await ctx.models.image(img.prompt, input.size);
        const mime = ["image/png", "image/jpeg", "image/webp"].find((m) => sniffMatches(out.bytes, m)) as "image/png" | "image/jpeg" | "image/webp" | undefined;
        if (!mime) {
          ctx.event("image_discarded", { index: i });
          continue;
        }
        const f = await ctx.tool("files.write", { name: `design-${i + 1}.${mime.split("/")[1]}`, mime, bytes: out.bytes, kind: "image" });
        images.push({ fileId: f.id, alt: img.alt });
      }
    }
    const md = `# Design brief\n\n${plan.concept}\n\n${plan.palette.length ? `**Palette:** ${plan.palette.join(", ")}\n\n` : ""}${plan.typography ? `**Typography:** ${plan.typography}\n\n` : ""}## Image prompts\n\n${plan.images.map((im, i) => `${i + 1}. ${im.prompt}\n   *Alt text:* ${im.alt}`).join("\n")}\n\n${generate ? `${images.length} image(s) generated.` : "No image-generation model is configured on this platform, so no images were generated."}\n`;
    const briefFile = await deliver(ctx, "design-brief.md", "text/markdown", md);
    const summary = generate ? `${images.length} image(s) and a design brief.` : "A design brief with image prompts (image generation isn't configured).";
    return finish(ctx, { images, briefFileId: briefFile.id, generated: images.length > 0, summary }, summary, [...images.map((i) => i.fileId), briefFile.id]);
  },
});

// ───────────── Translation and Localization ─────────────

const SRT_CUE = /(\d+)\s*\n(\d{2}:\d{2}:\d{2},\d{3} --> \d{2}:\d{2}:\d{2},\d{3})\s*\n([\s\S]*?)(?=\n\s*\n\d+\s*\n\d{2}:|\s*$)/g;

export function parseSrt(text: string): { timing: string; text: string }[] {
  return [...text.replace(/\r\n/g, "\n").matchAll(SRT_CUE)].map((m) => ({ timing: m[2]!, text: m[3]!.trim() }));
}

/** Replaces code, links and {placeholders} with markers a translation must keep. */
export function mask(text: string): { masked: string; tokens: string[] } {
  const tokens: string[] = [];
  const masked = text.replace(/```[\s\S]*?```|`[^`\n]+`|https?:\/\/[^\s)]+|\{\{?[A-Za-z0-9_.]+\}?\}|%[sd]/g, (m) => {
    tokens.push(m);
    return `⟦${tokens.length - 1}⟧`;
  });
  return { masked, tokens };
}

export function unmask(text: string, tokens: string[]): string | null {
  for (let i = 0; i < tokens.length; i++) if (text.split(`⟦${i}⟧`).length !== 2) return null;
  return text.replace(/⟦(\d+)⟧/g, (_m, i: string) => tokens[Number(i)] ?? "");
}

const TranslateInput = z.strictObject({
  brief: Brief,
  targetLanguage: z.string().trim().min(2).max(40).nullable().default(null),
  glossary: z.array(z.object({ term: z.string().max(100), translation: z.string().max(100) })).max(50).default([]),
});
const TranslateOutput = z.strictObject({ language: z.string(), files: z.array(z.strictObject({ source: z.string(), fileId: z.string(), kind: z.enum(["subtitles", "text"]), units: z.number().int() })), summary: Summary });

export const translateSkill = defineSkill<z.infer<typeof TranslateInput>, z.infer<typeof TranslateOutput>>({
  id: "translation.localize",
  version: 1,
  input: TranslateInput,
  output: TranslateOutput,
  tools: ["files.read", "files.write", "channel.post"],
  requiredTools: ["files.write"],
  categories: ["reasoning", "fast"],
  requiredCategories: ["reasoning"],
  files: { max: 10, maxBytes: 5 * MB, mimes: ["text/plain", "text/markdown", "application/json"] },
  limits: { maxRuntimeSec: 600, maxModelCalls: 40, maxAttempts: 2, maxOutputFiles: 10 },
  approvals: ["None."],
  errors: ["Subtitles keep every cue and timing; a batch whose cues don't match is retried once, then the job fails.", "Code, links and placeholders must survive translation unchanged, or that part is retried once and then fails."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const language =
      input.targetLanguage ??
      (await ctx.models.json("fast", z.object({ language: z.string().min(2).max(40).nullable() }), { prompt: `Which language should this be translated into? JSON {"language": name or null}.\n${untrusted("brief", input.brief)}`, maxOutputTokens: 50 })).language;
    if (!language) throw new ApiError(400, "bad_request", "Say which language to translate into.");
    const glossary = input.glossary.length ? `Always translate these terms as given: ${input.glossary.map((g) => `"${g.term}" → "${g.translation}"`).join("; ")}.` : "";
    const sources = await readTexts(ctx, ["text/plain", "text/markdown", "application/json"], 200_000);
    if (!sources.length) sources.push({ id: "brief", name: "text.md", text: input.brief });
    const files: z.infer<typeof TranslateOutput>["files"] = [];
    for (const [si, src] of sources.entries()) {
      ctx.progress(10 + (80 * si) / sources.length, `Translating ${src.name}`);
      const cues = parseSrt(src.text);
      if (cues.length) {
        const out: string[] = [];
        for (let b = 0; b < cues.length; b += 40) {
          const batch = cues.slice(b, b + 40);
          const want = batch.map((_, i) => b + i);
          const Batch = z.object({ cues: z.array(z.object({ i: z.number().int(), text: z.string().min(1).max(500) })) });
          let got: { i: number; text: string }[] = [];
          for (let attempt = 0; attempt < 2; attempt++) {
            got = (await ctx.models.json("reasoning", Batch, { system: `Translate subtitle cues into ${language}. Keep each cue short and keep the same "i". ${glossary} JSON only: {"cues":[{"i","text"}]}.`, prompt: untrusted(src.name, JSON.stringify(batch.map((c, k) => ({ i: b + k, text: c.text })))), maxOutputTokens: 4000 })).cues;
            if (got.length === want.length && want.every((i) => got.some((g) => g.i === i))) break;
            if (attempt === 1) throw new ApiError(502, "provider_error", "The translation lost or added subtitle cues.");
          }
          for (const i of want) out.push(got.find((g) => g.i === i)!.text);
        }
        const srt = cues.map((c, i) => `${i + 1}\n${c.timing}\n${out[i]}\n`).join("\n");
        const f = await deliver(ctx, src.name.replace(/(\.[^.]+)?$/, `.${language.toLowerCase().replace(/[^a-z-]/g, "").slice(0, 8)}.srt`), "application/x-subrip", srt);
        files.push({ source: src.name, fileId: f.id, kind: "subtitles", units: cues.length });
        continue;
      }
      const parts: string[] = [];
      let buf = "";
      for (const p of src.text.split(/\n\s*\n/)) {
        if (buf && buf.length + p.length > 8000) {
          parts.push(buf);
          buf = "";
        }
        buf = buf ? `${buf}\n\n${p}` : p;
      }
      if (buf) parts.push(buf);
      const translated: string[] = [];
      for (const part of parts) {
        const { masked, tokens } = mask(part);
        let result: string | null = null;
        for (let attempt = 0; attempt < 2 && result === null; attempt++) {
          const t = await ctx.models.text("reasoning", { system: `Translate into ${language}. Keep the formatting and every marker like ⟦0⟧ exactly once. ${glossary} Answer with the translation only.`, prompt: untrusted(src.name, masked), maxOutputTokens: 6000 });
          result = unmask(t.trim(), tokens);
        }
        if (result === null) throw new ApiError(502, "provider_error", "The translation dropped code, links or placeholders.");
        translated.push(result);
      }
      const ext = src.name.endsWith(".json") ? ".txt" : ".md";
      const f = await deliver(ctx, src.name.replace(/(\.[^.]+)?$/, `.${language.toLowerCase().replace(/[^a-z-]/g, "").slice(0, 8)}${ext}`), "text/markdown", translated.join("\n\n"));
      files.push({ source: src.name, fileId: f.id, kind: "text", units: parts.length });
    }
    const summary = `${files.length} file(s) translated into ${language}.`;
    return finish(ctx, { language, files, summary }, summary, files.map((f) => f.fileId));
  },
});
