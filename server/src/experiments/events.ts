import { z } from "zod";

/**
 * Product analytics events (docs/product/EXPERIMENTS.md §3). The catalogue is closed: an event the server doesn't know,
 * or a property outside its list of allowed values, is dropped. No property is free text, so message content, names,
 * email addresses, channel names and workspace names can't travel this way. The server keeps only daily counts (per
 * event, property values, platform and experiment variant), never who sent an event.
 */
const platform = z.enum(["android", "web"]);
const bucket = z.enum(["0", "1", "2-5", "6+"]);
const surface = z.enum(["home", "dms", "activity", "search", "channel", "thread", "notification", "directory", "other"]);
const kind = z.enum(["team", "community"]);

const props = {
  none: z.object({}).strict(),
  surface: z.object({ surface }).strict(),
  kind: z.object({ kind }).strict(),
  search: z.object({ results: bucket, scope: z.enum(["all", "messages", "people", "channels", "files", "apps"]) }).strict(),
  // Connector ids come from the server's own catalogue, so they name a product, never a person.
  call: z.object({ type: z.enum(["voice", "video", "room"]) }).strict(),
  error: z.object({ code: z.string().regex(/^[a-z0-9_]{2,40}$/) }).strict(),
  exposure: z.object({ experiment: z.string().regex(/^[a-z0-9-]{2,40}$/), variant: z.string().regex(/^[a-z0-9-]{2,40}$/) }).strict(),
} as const;

interface EventDef {
  props: z.ZodTypeAny;
  /** What [value] holds when the event carries a number (always milliseconds), or null. */
  value: "ms" | null;
  description: string;
}

export const ANALYTICS_EVENTS: Record<string, EventDef> = {
  "app.opened": { props: props.none, value: null, description: "The app came to the foreground signed in." },
  "onboarding.completed": { props: props.kind, value: "ms", description: "Finished the first-run steps; value: time from sign-up." },
  "first_action.done": { props: props.surface, value: "ms", description: "First meaningful action (a message sent, a call or a channel joined); value: time from sign-up." },
  "conversation.opened": { props: props.surface, value: null, description: "A conversation opened; surface: where from." },
  "message.sent": { props: props.surface, value: null, description: "A message sent (counted, never read)." },
  "community.created": { props: props.none, value: null, description: "A community created." },
  "workspace.created": { props: props.none, value: null, description: "A team workspace created." },
  "community.joined": { props: props.none, value: null, description: "Joined a community by invitation." },
  "channel.joined": { props: props.kind, value: null, description: "Joined a channel." },
  "search.performed": { props: props.search, value: null, description: "A search ran; results: how many came back, bucketed." },
  "search.result_opened": { props: props.search, value: null, description: "A search result opened." },
  "call.joined": { props: props.call, value: null, description: "Joined a call or voice room." },
  "error.shown": { props: props.error, value: null, description: "An error message shown; code: the server's error code." },
  "experiment.exposed": { props: props.exposure, value: null, description: "The person saw an experiment's variant (counted once per app start)." },
};

export const zAnalyticsBatch = z.object({
  platform,
  events: z
    .array(
      z.object({
        name: z.string().max(40),
        props: z.record(z.string().max(40), z.string().max(40)).optional(),
        value: z.number().finite().min(0).max(31 * 24 * 3600 * 1000).optional(),
      }),
    )
    .min(1)
    .max(50),
});

export interface CleanEvent {
  name: string;
  dims: string;
  value: number | null;
}

/** The events of a batch that fit the catalogue, with their properties in a canonical order. */
export function cleanEvents(batch: z.infer<typeof zAnalyticsBatch>): CleanEvent[] {
  const out: CleanEvent[] = [];
  for (const e of batch.events) {
    const def = ANALYTICS_EVENTS[e.name];
    if (!def) continue;
    const parsed = def.props.safeParse(e.props ?? {});
    if (!parsed.success) continue;
    const entries = Object.entries(parsed.data as Record<string, string>).sort(([a], [b]) => a.localeCompare(b));
    out.push({ name: e.name, dims: JSON.stringify(Object.fromEntries(entries)), value: def.value && e.value !== undefined ? Math.round(e.value) : null });
  }
  return out;
}
