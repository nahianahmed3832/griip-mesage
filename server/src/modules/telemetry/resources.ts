import fs from "node:fs";
import path from "node:path";
import { monitorEventLoopDelay } from "node:perf_hooks";

/**
 * Resource telemetry (docs/RESOURCE_MONITORING.md): what this process can really measure, once a minute.
 *
 * - From inside the API container (Linux): host CPU (/proc/stat), host memory (/proc/meminfo), load and uptime, host
 *   disk I/O (/proc/diskstats), the data volume's disk (statfs), the container's own network (/proc/net/dev is
 *   per network namespace), its cgroup CPU and memory, and this process (CPU time, RSS, event-loop delay).
 * - From the optional host collector (deploy/scripts/host-metrics.py, a systemd timer on the host, writing
 *   HOST_METRICS_FILE): the host's network interfaces and each Meldiq container's CPU, memory, network and block I/O;
 *   every other workload on the shared host only as one summed "other".
 *
 * Counters are cumulative at the source; rates are computed from the previous reading, so the first minute after a
 * restart has none. A series that can't be read has no sample (never a made-up zero).
 */

export interface Reader {
  read(file: string): string | null;
  statfs(dir: string): { total: number; free: number } | null;
}

export const fsReader: Reader = {
  read(file) {
    try {
      return fs.readFileSync(file, "utf8");
    } catch {
      return null;
    }
  },
  statfs(dir) {
    try {
      const s = fs.statfsSync(dir);
      return { total: s.blocks * s.bsize, free: s.bavail * s.bsize };
    } catch {
      return null;
    }
  },
};

export interface HostCollectorFile {
  version: 1;
  at: number;
  hostname?: string;
  os?: string;
  kernel?: string;
  cpus?: number;
  interfaces?: Record<string, { rx: number; tx: number; rxErrors: number; txErrors: number; rxDrops: number; txDrops: number }>;
  containers?: { group: string; cpuUsec: number; memBytes: number; memLimit: number | null; netRx: number; netTx: number; ioRead: number; ioWrite: number; count?: number }[];
}

/** The Meldiq services the collector reports by name; everything else on the host is "other". */
export const SERVICE_GROUPS = ["api", "livekit", "ntfy", "other"] as const;

/** How old the collector's file may be and still count as reporting. */
export const COLLECTOR_STALE_MS = 3 * 60_000;

export interface SamplerOptions {
  dataDir: string;
  databasePath: string;
  hostMetricsFile: string | null;
  reader?: Reader;
  clock?: () => number;
}

export interface Sample {
  series: string;
  value: number;
}

export interface CollectorStatus {
  configured: boolean;
  reporting: boolean;
  lastAt: number | null;
  hostname: string | null;
  os: string | null;
  kernel: string | null;
  cpus: number | null;
}

/** /proc/stat's first line → busy and total jiffies. */
export function parseCpu(stat: string): { busy: number; total: number } | null {
  const line = stat.split("\n").find((l) => l.startsWith("cpu "));
  if (!line) return null;
  const n = line.trim().split(/\s+/).slice(1).map(Number);
  if (n.length < 4 || n.some((x) => !Number.isFinite(x))) return null;
  const idle = n[3]! + (n[4] ?? 0);
  // guest time is already counted in user; steal counts as busy (the host took it).
  const total = n.slice(0, 8).reduce((a, b) => a + b, 0);
  return { busy: total - idle, total };
}

export function parseMeminfo(text: string): { total: number; available: number } | null {
  const kb = (k: string) => {
    const m = text.match(new RegExp(`^${k}:\\s+(\\d+) kB`, "m"));
    return m ? Number(m[1]) * 1024 : null;
  };
  const total = kb("MemTotal");
  const available = kb("MemAvailable");
  return total !== null && available !== null ? { total, available } : null;
}

/** /proc/net/dev → bytes per interface (loopback, docker bridges and veths left out). */
export function parseNetDev(text: string): Record<string, { rx: number; tx: number; rxErrors: number; txErrors: number; rxDrops: number; txDrops: number }> {
  const out: Record<string, { rx: number; tx: number; rxErrors: number; txErrors: number; rxDrops: number; txDrops: number }> = {};
  for (const line of text.split("\n").slice(2)) {
    const [name, rest] = line.split(":");
    if (!rest) continue;
    const iface = name!.trim();
    if (iface === "lo" || /^(veth|docker|br-)/.test(iface)) continue;
    const f = rest.trim().split(/\s+/).map(Number);
    out[iface] = { rx: f[0]!, rxErrors: f[2]!, rxDrops: f[3]!, tx: f[8]!, txErrors: f[10]!, txDrops: f[11]! };
  }
  return out;
}

/** /proc/diskstats → sectors read and written and ms busy, summed over whole disks (sdX, vdX, nvmeXnY, xvdX). */
export function parseDiskstats(text: string): { readBytes: number; writeBytes: number; busyMs: number } | null {
  let readBytes = 0;
  let writeBytes = 0;
  let busyMs = 0;
  let found = false;
  for (const line of text.split("\n")) {
    const f = line.trim().split(/\s+/);
    if (f.length < 14) continue;
    const dev = f[2]!;
    if (!/^(sd[a-z]+|vd[a-z]+|xvd[a-z]+|nvme\d+n\d+)$/.test(dev)) continue;
    found = true;
    readBytes += Number(f[5]) * 512;
    writeBytes += Number(f[9]) * 512;
    busyMs += Number(f[12]);
  }
  return found ? { readBytes, writeBytes, busyMs } : null;
}

/** cgroup v2 cpu.max ("max 100000" or "100000 100000") → the CPU limit in cores, null for no limit. */
export function parseCpuMax(text: string): number | null {
  const [quota, period] = text.trim().split(/\s+/);
  if (!quota || quota === "max" || !period) return null;
  const q = Number(quota);
  const p = Number(period);
  return q > 0 && p > 0 ? q / p : null;
}

const usecFromCpuStat = (text: string) => {
  const m = text.match(/^usage_usec (\d+)/m);
  return m ? Number(m[1]) : null;
};

interface Previous {
  at: number;
  cpu?: { busy: number; total: number };
  disk?: { readBytes: number; writeBytes: number; busyMs: number };
  net?: Record<string, { rx: number; tx: number; rxErrors: number; txErrors: number; rxDrops: number; txDrops: number }>;
  cgroupUsec?: number;
  processCpu?: NodeJS.CpuUsage;
  collector?: { at: number; interfaces?: HostCollectorFile["interfaces"]; containers: Map<string, NonNullable<HostCollectorFile["containers"]>[number]> };
}

export class ResourceSampler {
  private prev: Previous | null = null;
  private readonly reader: Reader;
  private readonly clock: () => number;
  private readonly loop = monitorEventLoopDelay({ resolution: 20 });
  private collector: CollectorStatus;

  constructor(private readonly opts: SamplerOptions) {
    this.reader = opts.reader ?? fsReader;
    this.clock = opts.clock ?? Date.now;
    this.loop.enable();
    this.collector = { configured: !!opts.hostMetricsFile, reporting: false, lastAt: null, hostname: null, os: null, kernel: null, cpus: null };
  }

  stop(): void {
    this.loop.disable();
  }

  collectorStatus(): CollectorStatus {
    const t = this.clock();
    return { ...this.collector, reporting: this.collector.lastAt !== null && t - this.collector.lastAt < COLLECTOR_STALE_MS };
  }

  /** One reading of everything measurable; rates need the previous reading. */
  sample(): Sample[] {
    const t = this.clock();
    const out: Sample[] = [];
    const push = (series: string, value: number | null | undefined) => {
      if (value !== null && value !== undefined && Number.isFinite(value)) out.push({ series, value: Math.round(value * 100) / 100 });
    };
    const prev = this.prev;
    const seconds = prev ? (t - prev.at) / 1000 : 0;
    const rate = (now: number, before: number | undefined) => (prev && before !== undefined && seconds > 0 && now >= before ? (now - before) / seconds : null);
    const next: Previous = { at: t };

    const cpu = parseCpu(this.reader.read("/proc/stat") ?? "");
    if (cpu) {
      next.cpu = cpu;
      if (prev?.cpu && cpu.total > prev.cpu.total) push("host.cpu.pct", ((cpu.busy - prev.cpu.busy) / (cpu.total - prev.cpu.total)) * 100);
    }
    const mem = parseMeminfo(this.reader.read("/proc/meminfo") ?? "");
    if (mem) {
      push("host.mem.total_bytes", mem.total);
      push("host.mem.used_bytes", mem.total - mem.available);
    }
    const load = (this.reader.read("/proc/loadavg") ?? "").split(" ").map(Number);
    if (load.length >= 3 && Number.isFinite(load[0])) push("host.load1", load[0]);
    const uptime = Number((this.reader.read("/proc/uptime") ?? "").split(" ")[0]);
    if (uptime > 0) push("host.uptime_s", uptime);

    const disk = this.reader.statfs(this.opts.dataDir);
    if (disk) {
      push("host.disk.total_bytes", disk.total);
      push("host.disk.used_bytes", disk.total - disk.free);
    }
    const io = parseDiskstats(this.reader.read("/proc/diskstats") ?? "");
    if (io) {
      next.disk = io;
      push("host.disk.read_bps", rate(io.readBytes, prev?.disk?.readBytes));
      push("host.disk.write_bps", rate(io.writeBytes, prev?.disk?.writeBytes));
      const busy = rate(io.busyMs, prev?.disk?.busyMs);
      if (busy !== null) push("host.disk.util_pct", Math.min(100, busy / 10));
    }

    // The container's own network namespace: all of the API's traffic, nothing else's.
    const net = this.reader.read("/proc/net/dev");
    if (net) {
      const ifaces = parseNetDev(net);
      next.net = ifaces;
      const sum = (o: typeof ifaces, k: "rx" | "tx") => Object.values(o).reduce((a, i) => a + i[k], 0);
      if (prev?.net) {
        push("api.net.rx_bps", rate(sum(ifaces, "rx"), sum(prev.net, "rx")));
        push("api.net.tx_bps", rate(sum(ifaces, "tx"), sum(prev.net, "tx")));
      }
    }

    const cpuMax = this.reader.read("/sys/fs/cgroup/cpu.max");
    const limitCores = cpuMax ? parseCpuMax(cpuMax) : null;
    const usec = usecFromCpuStat(this.reader.read("/sys/fs/cgroup/cpu.stat") ?? "");
    if (usec !== null) {
      next.cgroupUsec = usec;
      const used = rate(usec, prev?.cgroupUsec);
      if (used !== null) {
        push("api.cpu.pct", used / 10_000);
        if (limitCores) push("api.cpu.limit_pct", used / 10_000 / limitCores);
      }
    }
    if (limitCores) push("api.cpu.limit_cores", limitCores);
    const cgMem = Number((this.reader.read("/sys/fs/cgroup/memory.current") ?? "").trim());
    if (cgMem > 0) push("api.mem.cgroup_bytes", cgMem);
    const memMax = (this.reader.read("/sys/fs/cgroup/memory.max") ?? "").trim();
    if (/^\d+$/.test(memMax)) push("api.mem.limit_bytes", Number(memMax));

    const processCpu = process.cpuUsage();
    next.processCpu = processCpu;
    if (prev?.processCpu && seconds > 0) push("api.process.cpu_pct", ((processCpu.user + processCpu.system - prev.processCpu.user - prev.processCpu.system) / 1e6 / seconds) * 100);
    push("api.mem.rss_bytes", process.memoryUsage().rss);
    if (this.loop.count > 0) push("api.eventloop.p99_ms", this.loop.percentile(99) / 1e6);
    this.loop.reset();

    const dbSize = fileSize(this.opts.databasePath);
    push("db.size_bytes", dbSize);
    push("db.wal_bytes", fileSize(`${this.opts.databasePath}-wal`));

    this.readCollector(t, prev, next, push);
    this.prev = next;
    return out;
  }

  private readCollector(t: number, prev: Previous | null, next: Previous, push: (s: string, v: number | null | undefined) => void): void {
    if (!this.opts.hostMetricsFile) return;
    const raw = this.reader.read(this.opts.hostMetricsFile);
    if (!raw) return;
    let file: HostCollectorFile;
    try {
      file = JSON.parse(raw) as HostCollectorFile;
    } catch {
      return;
    }
    if (file.version !== 1 || typeof file.at !== "number" || t - file.at > COLLECTOR_STALE_MS) return;
    this.collector = { configured: true, reporting: true, lastAt: file.at, hostname: file.hostname ?? null, os: file.os ?? null, kernel: file.kernel ?? null, cpus: file.cpus ?? null };
    const containers = new Map<string, NonNullable<HostCollectorFile["containers"]>[number]>();
    for (const c of file.containers ?? []) if ((SERVICE_GROUPS as readonly string[]).includes(c.group)) containers.set(c.group, c);
    next.collector = { at: file.at, interfaces: file.interfaces, containers };
    const p = prev?.collector;
    const seconds = p ? (file.at - p.at) / 1000 : 0;
    // The same file read twice (the collector hasn't written since): keep the older reading as the base.
    if (p && seconds <= 0) {
      next.collector = p;
      return;
    }
    const rate = (now: number, before: number | undefined) => (p && before !== undefined && seconds > 0 && now >= before ? (now - before) / seconds : null);
    const ifaces = file.interfaces ?? {};
    const sum = (o: NonNullable<HostCollectorFile["interfaces"]> | undefined, k: "rx" | "tx" | "rxErrors" | "txErrors" | "rxDrops" | "txDrops") =>
      Object.values(o ?? {}).reduce((a, i) => a + (i[k] ?? 0), 0);
    if (p?.interfaces) {
      push("host.net.rx_bps", rate(sum(ifaces, "rx"), sum(p.interfaces, "rx")));
      push("host.net.tx_bps", rate(sum(ifaces, "tx"), sum(p.interfaces, "tx")));
      const errors = sum(ifaces, "rxErrors") + sum(ifaces, "txErrors") - sum(p.interfaces, "rxErrors") - sum(p.interfaces, "txErrors");
      const drops = sum(ifaces, "rxDrops") + sum(ifaces, "txDrops") - sum(p.interfaces, "rxDrops") - sum(p.interfaces, "txDrops");
      if (errors >= 0) push("host.net.errors", errors);
      if (drops >= 0) push("host.net.drops", drops);
    }
    for (const [group, c] of containers) {
      push(`svc.${group}.mem_bytes`, c.memBytes);
      if (c.memLimit) push(`svc.${group}.mem_limit_bytes`, c.memLimit);
      const before = p?.containers.get(group);
      if (!before) continue;
      const cpu = rate(c.cpuUsec, before.cpuUsec);
      if (cpu !== null) push(`svc.${group}.cpu_pct`, cpu / 10_000);
      push(`svc.${group}.net_rx_bps`, rate(c.netRx, before.netRx));
      push(`svc.${group}.net_tx_bps`, rate(c.netTx, before.netTx));
      push(`svc.${group}.io_read_bps`, rate(c.ioRead, before.ioRead));
      push(`svc.${group}.io_write_bps`, rate(c.ioWrite, before.ioWrite));
    }
  }
}

function fileSize(file: string): number | null {
  try {
    return fs.statSync(file).size;
  } catch {
    return null;
  }
}

/** HOST_METRICS_FILE: where the host collector writes (production: /host-metrics/metrics.json, mounted read-only). */
export function hostMetricsFile(env: NodeJS.ProcessEnv): string | null {
  const file = env.HOST_METRICS_FILE?.trim();
  return file ? path.resolve(file) : null;
}
