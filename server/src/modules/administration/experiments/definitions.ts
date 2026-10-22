/**
 * Product experiments (docs/product/EXPERIMENTS.md). Definitions live in code so every change is reviewed; whether
 * they run is the operator's switch (EXPERIMENTS_MODE, EXPERIMENT_STATUS), never a client's.
 *
 * Every experiment starts as "draft": nobody is assigned until the owner approves it and the operator turns it on.
 * Experiments in one [layer] never overlap: a person is in at most one experiment per layer, so two incompatible
 * variants (two navigation layouts, say) can't meet on one screen.
 */
export type ExperimentStatus = "draft" | "preview" | "running" | "stopped";
export const EXPERIMENT_STATUSES: readonly ExperimentStatus[] = ["draft", "preview", "running", "stopped"];

export interface Variant {
  id: string;
  weight: number;
}

export interface ExperimentDef {
  id: string;
  description: string;
  /** Mutually exclusive group: navigation, onboarding, directory… */
  layer: string;
  /** Share of the layer's people this experiment may take, 0–1. Shares in one layer add up to at most 1. */
  traffic: number;
  /** The established experience. Everyone outside the experiment, and everyone on rollback, gets it. */
  control: string;
  variants: readonly Variant[];
  status: ExperimentStatus;
  /** ISO dates (UTC). Outside them a running experiment assigns nobody. */
  startsAt: string | null;
  endsAt: string | null;
  /** Event names (analytics/events.ts) that decide it; the first is the primary metric. */
  metrics: readonly string[];
  /** In plain words, what has to happen for the variant to win. */
  successCriteria: string;
}

export const EXPERIMENTS: readonly ExperimentDef[] = [
  {
    id: "home-layout",
    description: "Home as one list of channels and DMs grouped by section (control) or as cards for each area first (cards).",
    layer: "navigation",
    traffic: 0.5,
    control: "sections",
    variants: [
      { id: "sections", weight: 1 },
      { id: "cards", weight: 1 },
    ],
    status: "draft",
    startsAt: null,
    endsAt: null,
    metrics: ["conversation.opened", "search.performed", "app.opened"],
    successCriteria: "More conversations opened from Home per active day, with no rise in searches for a conversation people already belong to.",
  },
  {
    id: "community-onboarding",
    description: "New community members land in #welcome with a short checklist (checklist) or in the community's channel list (control).",
    layer: "onboarding",
    traffic: 1,
    control: "channel-list",
    variants: [
      { id: "channel-list", weight: 1 },
      { id: "checklist", weight: 1 },
    ],
    status: "draft",
    startsAt: null,
    endsAt: null,
    metrics: ["community.joined", "message.sent", "onboarding.completed"],
    successCriteria: "A higher share of people who join a community send their first message there within a day.",
  },
];

/** Problems with the definitions (empty when they're sound); unit-tested so a bad edit fails CI, not production. */
export function definitionProblems(defs: readonly ExperimentDef[] = EXPERIMENTS): string[] {
  const problems: string[] = [];
  const ids = new Set<string>();
  const traffic = new Map<string, number>();
  for (const d of defs) {
    if (!/^[a-z0-9-]{2,40}$/.test(d.id)) problems.push(`${d.id}: id must be lower-case words with hyphens`);
    if (ids.has(d.id)) problems.push(`${d.id}: duplicate id`);
    ids.add(d.id);
    if (d.variants.length < 2) problems.push(`${d.id}: needs at least two variants`);
    if (!d.variants.some((v) => v.id === d.control)) problems.push(`${d.id}: control ${d.control} isn't a variant`);
    if (d.variants.some((v) => !(v.weight > 0))) problems.push(`${d.id}: weights must be positive`);
    if (new Set(d.variants.map((v) => v.id)).size !== d.variants.length) problems.push(`${d.id}: duplicate variant`);
    if (!(d.traffic > 0 && d.traffic <= 1)) problems.push(`${d.id}: traffic must be in (0, 1]`);
    if (d.metrics.length === 0) problems.push(`${d.id}: needs a primary metric`);
    traffic.set(d.layer, (traffic.get(d.layer) ?? 0) + d.traffic);
  }
  for (const [layer, total] of traffic) if (total > 1 + 1e-9) problems.push(`layer ${layer}: traffic adds up to ${total}`);
  return problems;
}
