/**
 * In-process operational metrics (docs/OPERATIONS.md, round 7 slice C1): what the server measures about itself, for
 * the platform admins' health view. Nothing here holds content, user ids, conversation ids or addresses: requests are
 * keyed by their route *template* ("/v1/conversations/:id"), jobs and providers by name.
 *
 * Kept in memory only and reset when the process restarts (the health view says since when). One API process today;
 * a second process would need these exported to a shared store first.
 */

/** Upper bounds of the latency buckets, in milliseconds (the last bucket is everything slower). */
export const LATENCY_BUCKETS_MS = [10, 25, 50, 100, 250, 500, 1000, 2500, 5000] as const;
/** Minutes of per-minute totals kept for "right now" rates. */
export const WINDOW_MINUTES = 15;

interface RouteStats {
  count: number;
  clientErrors: number;
  serverErrors: number;
  totalMs: number;
  maxMs: number;
  buckets: number[];
}

interface MinuteTotals {
  minute: number;
  requests: number;
  serverErrors: number;
  buckets: number[];
}

export interface JobStats {
  name: string;
  runs: number;
  failures: number;
  lastRunAt: number | null;
  lastDurationMs: number | null;
  lastFailureAt: number | null;
  lastErrorKind: string | null;
}

export interface ProviderStats {
  name: string;
  ok: number;
  failed: number;
  lastOkAt: number | null;
  lastFailureAt: number | null;
  lastErrorKind: string | null;
}

export interface RouteView {
  route: string;
  count: number;
  clientErrors: number;
  serverErrors: number;
  avgMs: number;
  p95Ms: number | null;
  maxMs: number;
}

export interface MetricsSnapshot {
  since: number;
  window: { minutes: number; requests: number; serverErrors: number; errorRate: number | null; p95Ms: number | null };
  routes: RouteView[];
  jobs: JobStats[];
  providers: ProviderStats[];
}

const emptyBuckets = () => new Array<number>(LATENCY_BUCKETS_MS.length + 1).fill(0);

function bucketOf(ms: number): number {
  const i = LATENCY_BUCKETS_MS.findIndex((b) => ms <= b);
  return i === -1 ? LATENCY_BUCKETS_MS.length : i;
}

/** The 95th percentile as the upper bound of the bucket it falls in; null without samples (or beyond the last bound). */
export function p95(buckets: readonly number[]): number | null {
  const total = buckets.reduce((a, b) => a + b, 0);
  if (total === 0) return null;
  const target = Math.ceil(total * 0.95);
  let seen = 0;
  for (let i = 0; i < buckets.length; i++) {
    seen += buckets[i];
    if (seen >= target) return i < LATENCY_BUCKETS_MS.length ? LATENCY_BUCKETS_MS[i] : null;
  }
  return null;
}

/** An error's kind for the health view: its class or code, never its message (messages can carry data). */
export function errorKind(e: unknown): string {
  if (e && typeof e === "object") {
    const code = (e as { code?: unknown }).code;
    if (typeof code === "string" && /^[A-Za-z0-9_.-]{1,40}$/.test(code)) return code;
    const name = (e as { name?: unknown }).name;
    if (typeof name === "string" && name !== "Error") return name.slice(0, 40);
  }
  return "error";
}

export class Metrics {
  readonly since: number;
  private routes = new Map<string, RouteStats>();
  private minutes: MinuteTotals[] = [];
  private jobs = new Map<string, JobStats>();
  private providers = new Map<string, ProviderStats>();

  constructor(private readonly clock: () => number = Date.now) {
    this.since = clock();
  }

  /** One finished request: [route] is the matched route template, or "unmatched". */
  recordRequest(method: string, route: string, status: number, ms: number): void {
    const key = `${method} ${route}`;
    const r = this.routes.get(key) ?? { count: 0, clientErrors: 0, serverErrors: 0, totalMs: 0, maxMs: 0, buckets: emptyBuckets() };
    const b = bucketOf(ms);
    r.count += 1;
    if (status >= 500) r.serverErrors += 1;
    else if (status >= 400) r.clientErrors += 1;
    r.totalMs += ms;
    r.maxMs = Math.max(r.maxMs, ms);
    r.buckets[b] += 1;
    this.routes.set(key, r);
    const m = this.currentMinute();
    m.requests += 1;
    if (status >= 500) m.serverErrors += 1;
    m.buckets[b] += 1;
  }

  /** One run of a background job; [error] when it failed. */
  recordJob(name: string, ms: number, error?: unknown): void {
    const j = this.jobs.get(name) ?? { name, runs: 0, failures: 0, lastRunAt: null, lastDurationMs: null, lastFailureAt: null, lastErrorKind: null };
    const t = this.clock();
    this.jobs.set(name, {
      ...j,
      runs: j.runs + 1,
      failures: j.failures + (error === undefined ? 0 : 1),
      lastRunAt: t,
      lastDurationMs: Math.round(ms),
      lastFailureAt: error === undefined ? j.lastFailureAt : t,
      lastErrorKind: error === undefined ? j.lastErrorKind : errorKind(error),
    });
  }

  /** Times [fn] as a run of job [name] and records it; rethrows its error. */
  async timeJob<T>(name: string, fn: () => Promise<T>): Promise<T> {
    const t0 = performance.now();
    try {
      const out = await fn();
      this.recordJob(name, performance.now() - t0);
      return out;
    } catch (e) {
      this.recordJob(name, performance.now() - t0, e);
      throw e;
    }
  }

  /** One call to an outside service (push, email, storage, a connector); [error] when it failed. */
  recordProvider(name: string, error?: unknown): void {
    const p = this.providers.get(name) ?? { name, ok: 0, failed: 0, lastOkAt: null, lastFailureAt: null, lastErrorKind: null };
    const t = this.clock();
    this.providers.set(
      name,
      error === undefined
        ? { ...p, ok: p.ok + 1, lastOkAt: t }
        : { ...p, failed: p.failed + 1, lastFailureAt: t, lastErrorKind: errorKind(error) },
    );
  }

  snapshot(): MetricsSnapshot {
    const nowMinute = Math.floor(this.clock() / 60_000);
    const recent = this.minutes.filter((m) => m.minute > nowMinute - WINDOW_MINUTES);
    const requests = recent.reduce((a, m) => a + m.requests, 0);
    const serverErrors = recent.reduce((a, m) => a + m.serverErrors, 0);
    const buckets = emptyBuckets().map((_, i) => recent.reduce((a, m) => a + m.buckets[i], 0));
    return {
      since: this.since,
      window: { minutes: WINDOW_MINUTES, requests, serverErrors, errorRate: requests ? serverErrors / requests : null, p95Ms: p95(buckets) },
      routes: [...this.routes.entries()]
        .map(([route, r]) => ({
          route,
          count: r.count,
          clientErrors: r.clientErrors,
          serverErrors: r.serverErrors,
          avgMs: Math.round(r.totalMs / r.count),
          p95Ms: p95(r.buckets),
          maxMs: Math.round(r.maxMs),
        }))
        .sort((a, b) => b.serverErrors - a.serverErrors || b.count - a.count),
      jobs: [...this.jobs.values()].sort((a, b) => a.name.localeCompare(b.name)),
      providers: [...this.providers.values()].sort((a, b) => a.name.localeCompare(b.name)),
    };
  }

  private currentMinute(): MinuteTotals {
    const minute = Math.floor(this.clock() / 60_000);
    const last = this.minutes[this.minutes.length - 1];
    if (last?.minute === minute) return last;
    const fresh = { minute, requests: 0, serverErrors: 0, buckets: emptyBuckets() };
    this.minutes = [...this.minutes.filter((m) => m.minute > minute - WINDOW_MINUTES), fresh];
    return fresh;
  }
}
