import { z } from "zod/v4";
import { ApiError } from "../../../../shared/errors.js";
import { untrusted } from "../../prompting.js";
import { defineSkill } from "../../types.js";
import { Brief, deliver, finish, htmlToText, MB, passages, quoteFound, rank, readTexts, type Source, sourcesBlock, Summary, TEXT_MIMES } from "../common.js";

/** General Assistant: questions, short tasks and summaries of what's sent with them. */
const GeneralInput = z.strictObject({ brief: Brief });
const GeneralOutput = z.strictObject({ answer: z.string(), fileId: z.string().nullable(), summary: Summary });

export const generalSkill = defineSkill<z.infer<typeof GeneralInput>, z.infer<typeof GeneralOutput>>({
  id: "general.assist",
  version: 1,
  input: GeneralInput,
  output: GeneralOutput,
  tools: ["files.read", "files.write", "channel.post"],
  requiredTools: [],
  categories: ["fast", "reasoning"],
  requiredCategories: ["fast"],
  files: { max: 5, maxBytes: 5 * MB, mimes: TEXT_MIMES },
  limits: { maxRuntimeSec: 120, maxModelCalls: 2, maxAttempts: 2, maxOutputFiles: 1 },
  approvals: ["None: it only answers."],
  errors: ["No text model configured: the job fails with ai_not_configured."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const sources = await readTexts(ctx, TEXT_MIMES, 60_000);
    const answer = await ctx.models.text(sources.length ? "reasoning" : "fast", {
      system: "Answer helpfully and accurately. If you don't know, say so. Use the attached material when it's relevant.",
      prompt: `${untrusted("request", input.brief)}${sources.length ? `\n\nAttached material:\n${sourcesBlock(sources)}` : ""}`,
      maxOutputTokens: 2000,
    });
    // Long answers also become a document, so they're easy to keep.
    const file = answer.length > 3000 ? await deliver(ctx, "answer.md", "text/markdown", answer) : null;
    const summary = answer.slice(0, 280);
    return finish(ctx, { answer, fileId: file?.id ?? null, summary }, summary, file ? [file.id] : [], answer.slice(0, 7000));
  },
});

/** Research and Knowledge: a referenced report from the documents sent (and public pages, if allowed). */
const ResearchInput = z.strictObject({
  brief: Brief,
  urls: z.array(z.string().url().max(500)).max(5).default([]),
});
const Citation = z.object({ source: z.string(), quote: z.string().max(400) });
const Draft = z.object({
  title: z.string().max(150),
  sections: z.array(z.object({ heading: z.string().max(150), paragraphs: z.array(z.object({ text: z.string().max(3000), citations: z.array(Citation).max(6).default([]) })).min(1).max(12) })).min(1).max(12),
  gaps: z.array(z.string().max(300)).max(10).default([]),
});
const ResearchOutput = z.strictObject({
  title: z.string(),
  sources: z.array(z.strictObject({ id: z.string(), name: z.string() })),
  citations: z.strictObject({ verified: z.number().int(), dropped: z.number().int() }),
  gaps: z.array(z.string()),
  reportFileId: z.string(),
  summary: Summary,
});

export const researchSkill = defineSkill<z.infer<typeof ResearchInput>, z.infer<typeof ResearchOutput>>({
  id: "research.report",
  version: 1,
  input: ResearchInput,
  output: ResearchOutput,
  tools: ["files.read", "files.write", "channel.post", "web.fetch"],
  requiredTools: ["files.read", "files.write"],
  categories: ["reasoning", "embeddings"],
  requiredCategories: ["reasoning"],
  files: { max: 10, maxBytes: 10 * MB, mimes: TEXT_MIMES },
  limits: { maxRuntimeSec: 300, maxModelCalls: 3, maxAttempts: 2, maxOutputFiles: 1 },
  approvals: ["None. Fetching public pages needs the web.fetch tool, which the platform turns on and the community grants."],
  errors: ["No documents and no pages: refused.", "A citation whose quote isn't in its source is dropped and counted; the report says how many."],
  tests: ["ai_workforce_skills.test.ts"],
  async run(ctx, input) {
    const sources: Source[] = await readTexts(ctx);
    for (const [i, url] of input.urls.entries()) {
      try {
        const page = await ctx.tool("web.fetch", { url });
        sources.push({ id: `web${i + 1}`, name: page.url, text: (page.contentType === "text/html" ? htmlToText(page.text) : page.text).slice(0, 60_000) });
      } catch (e) {
        if (!(e instanceof ApiError)) throw e;
        ctx.event("source_skipped", { index: i, code: e.code });
      }
    }
    if (!sources.length) throw new ApiError(400, "bad_request", "Send documents to research (or public page addresses, if fetching pages is allowed here).");
    ctx.progress(20, "Reading the sources");
    const relevant = rank(input.brief, passages(sources), 40);
    const material = relevant.map((p) => untrusted(`${p.sourceId}#${p.index} (${p.sourceName})`, p.text)).join("\n\n");
    ctx.progress(45, "Writing the report");
    const draft = await ctx.models.json("reasoning", Draft, {
      system:
        'Write a research report answering the brief from the sources only. Every factual claim cites a source: {"source": the source id before "#", "quote": an exact sentence copied from it}. Say in "gaps" what the sources don\'t cover. JSON only: {"title","sections":[{"heading","paragraphs":[{"text","citations"}]}],"gaps"}.',
      prompt: `Brief:\n${untrusted("brief", input.brief)}\n\nSources:\n${material}`,
      maxOutputTokens: 4000,
    });
    const byId = new Map(sources.map((s) => [s.id, s]));
    const numbers = new Map<string, number>();
    let verified = 0;
    let dropped = 0;
    const body = draft.sections
      .map((sec) => {
        const paras = sec.paragraphs.map((p) => {
          const refs = p.citations.flatMap((c) => {
            const src = byId.get(c.source.split("#")[0]!);
            if (!src || !quoteFound(c.quote, src.text)) {
              dropped++;
              return [];
            }
            verified++;
            if (!numbers.has(src.id)) numbers.set(src.id, numbers.size + 1);
            return [`[${numbers.get(src.id)}]`];
          });
          return `${p.text}${refs.length ? ` ${[...new Set(refs)].join("")}` : p.citations.length ? " *(citation not verifiable)*" : ""}`;
        });
        return `## ${sec.heading}\n\n${paras.join("\n\n")}`;
      })
      .join("\n\n");
    const refs = [...numbers.entries()].map(([id, n]) => `${n}. ${byId.get(id)!.name}`).join("\n");
    const md = `# ${draft.title}\n\n${body}\n\n## Sources\n\n${refs || "No claim could be verified against the sources."}\n${draft.gaps.length ? `\n## Not covered by the sources\n\n${draft.gaps.map((g) => `- ${g}`).join("\n")}\n` : ""}\n---\nCitations checked against the sources: ${verified} verified, ${dropped} dropped.\n`;
    const file = await deliver(ctx, "research-report.md", "text/markdown", md);
    const summary = `"${draft.title}": ${draft.sections.length} sections, ${verified} verified citations from ${numbers.size} of ${sources.length} sources${dropped ? `, ${dropped} unverifiable citations dropped` : ""}.`;
    return finish(ctx, { title: draft.title, sources: sources.map((s) => ({ id: s.id, name: s.name })), citations: { verified, dropped }, gaps: draft.gaps, reportFileId: file.id, summary }, summary, [file.id]);
  },
});
