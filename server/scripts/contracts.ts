import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { VERSION } from "../src/app/server.js";
import { buildOpenApi, buildRealtimeSchema } from "../src/shared/contracts/openapi.js";

/** Writes docs/api/openapi.json and docs/api/realtime.schema.json from src/shared/contracts. */
export const API_DOCS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..", "docs", "api");

export function renderContracts(): Record<string, string> {
  return {
    "openapi.json": `${JSON.stringify(buildOpenApi(VERSION), null, 2)}\n`,
    "realtime.schema.json": `${JSON.stringify(buildRealtimeSchema(), null, 2)}\n`,
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  fs.mkdirSync(API_DOCS, { recursive: true });
  for (const [name, text] of Object.entries(renderContracts())) fs.writeFileSync(path.join(API_DOCS, name), text);
  process.stdout.write(`wrote ${Object.keys(renderContracts()).join(", ")} to docs/api\n`);
}
