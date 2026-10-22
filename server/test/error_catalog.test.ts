import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { ERROR_CODES, type ErrorCode } from "../src/shared/errors.js";

/**
 * The error-code catalog (src/shared/errors.ts) against the code: the compiler already refuses a code that isn't
 * listed; this checks the statuses and that nothing listed is dead.
 */

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(here, "..", "src");
const CATALOG_FILE = path.join(SRC, "shared", "errors.ts");

const walk = (dir: string): string[] =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) return walk(p);
    return p.endsWith(".ts") ? [p] : [];
  });

const sources = walk(SRC).filter((f) => f !== CATALOG_FILE).map((f) => ({ file: path.relative(SRC, f), text: fs.readFileSync(f, "utf8") }));

/** Places where a status and a code are written side by side. */
const WITH_STATUS = [
  /new (?:ApiError|FederationError)\(\s*(\d{3}),\s*"([a-z_]+)"/g,
  /\brefuse\(\s*(\d{3}),\s*"([a-z_]+)"/g,
  /\{\s*status:\s*(\d{3}),\s*code:\s*"([a-z_]+)"/g,
  /reply\.code\((\d{3})\)\.send\(\{\s*error:\s*\{\s*code:\s*"([a-z_]+)"/g,
];

describe("error-code catalog", () => {
  it("lists every status a code is thrown with", () => {
    const wrong: string[] = [];
    for (const { file, text } of sources) {
      for (const re of WITH_STATUS) {
        for (const m of text.matchAll(re)) {
          const status = Number(m[1]);
          const code = m[2]! as ErrorCode;
          const spec = ERROR_CODES[code];
          // Federation answers to peer servers use their own codes (docs/FEDERATION_PROTOCOL.md); clients never see them.
          if (!spec && file.startsWith("modules/federation/")) continue;
          if (!spec || !spec.status.includes(status)) wrong.push(`${file}: ${code} ${status}`);
        }
      }
    }
    expect(wrong).toEqual([]);
  });

  it("has no codes the server never sends", () => {
    // errors.ts itself counts outside the catalog: its helpers (badRequest, conflict, …) send codes too.
    const catalogFile = fs.readFileSync(CATALOG_FILE, "utf8");
    const helpers = catalogFile.slice(catalogFile.indexOf("export class ApiError"));
    const all = [...sources.map((s) => s.text), helpers].join("\n");
    const unused = Object.keys(ERROR_CODES).filter((code) => !all.includes(`"${code}"`));
    expect(unused).toEqual([]);
  });

  it("describes every code", () => {
    const bad = Object.entries(ERROR_CODES).filter(([, s]) => s.meaning.trim().length < 8 || s.status.some((n) => n < 400 || n > 599));
    expect(bad).toEqual([]);
  });
});
