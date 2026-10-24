import type { AgentDef } from "../types.js";
import { AGENTS } from "./agents.js";

export { AGENTS } from "./agents.js";

/** One version of an agent, or its newest version when [version] is left out. */
export function findAgent(id: string, version?: number): AgentDef | null {
  const all = AGENTS.filter((a) => a.id === id);
  if (version !== undefined) return all.find((a) => a.version === version) ?? null;
  return all.reduce<AgentDef | null>((best, a) => (!best || a.version > best.version ? a : best), null);
}
