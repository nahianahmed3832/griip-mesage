/**
 * Helpers for what goes into and comes out of a model (no imports, so skills, the runtime and the catalog can all use
 * them without depending on each other).
 */

/** Wraps material from people or files so a model treats it as data. Closing tags inside it are neutralised. */
export function untrusted(label: string, text: string): string {
  // The label keeps ids like "aif_x#3 (notes.md)" readable; quotes and angle brackets can't end the tag early.
  return `<untrusted source="${label.replace(/[^A-Za-z0-9 ._#():/-]/g, "")}">\n${text.replace(/<\/?untrusted/gi, "<_untrusted")}\n</untrusted>`;
}

/** The first JSON object or array in a model's answer (models sometimes wrap it in a code fence). */
export function extractJson(text: string): unknown {
  const fenced = /```(?:json)?\s*([\s\S]*?)```/.exec(text)?.[1];
  const raw = (fenced ?? text).trim();
  const start = raw.search(/[[{]/);
  if (start < 0) throw new Error("no JSON");
  const open = raw[start]!;
  const close = open === "{" ? "}" : "]";
  return JSON.parse(raw.slice(start, raw.lastIndexOf(close) + 1));
}
