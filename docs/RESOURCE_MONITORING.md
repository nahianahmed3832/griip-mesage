# Resource monitoring (Platform Admin 2.0, API 0.34.0)

What the console can see of the server, how, and what it can't. No figure is invented: a series that can't be read
has no sample, and the console lists what isn't measured and why.

## Sources

| Source | Series | Notes |
|---|---|---|
| The API process, from inside its container | `host.cpu.pct`, `host.mem.*`, `host.load1`, `host.uptime_s` (from /proc, which isn't namespaced); `host.disk.*` (statfs of the data volume) and `host.disk.read_bps`/`write_bps`/`util_pct` (/proc/diskstats); `api.net.*` (its own network namespace: all of the API's traffic); `api.cpu.*` and `api.mem.cgroup_bytes`/`limit_bytes` (its cgroup); `api.process.cpu_pct`, `api.mem.rss_bytes`, `api.eventloop.p99_ms`; `db.size_bytes`, `db.wal_bytes` | Linux only. Host figures are the whole shared machine |
| Request metrics | `api.requests`, `api.errors_5xx`, `api.errors_4xx`, `api.latency.p50_ms`/`p95_ms`/`p99_ms` per minute | Percentiles are latency-bucket upper bounds (10, 25, 50, 100, 250, 500 ms, 1, 2.5, 5 s) |
| The file store, hourly | `storage.files_bytes`, `storage.files_count` | All live attachments |
| The host collector (optional) | `host.net.rx_bps`/`tx_bps`, `host.net.errors`/`drops`; `svc.<api|livekit|ntfy|other>.cpu_pct`, `mem_bytes`, `mem_limit_bytes`, `net_rx_bps`/`net_tx_bps`, `io_read_bps`/`io_write_bps` | Every other workload on the shared host is one summed group, `other`: no names leave the host |

Samples are written once a minute (`resource_samples`, kept 7 days) and rolled up hourly (`resource_hourly`: average,
minimum, maximum, sample count; kept 400 days). Rates come from the previous reading, so the first minute after a
restart has none.

## The host collector

`deploy/scripts/host-metrics.py` (Python 3, standard library only) runs every 30 seconds from a systemd timer
(`deploy/systemd/meldiq-host-metrics.{service,timer}`). As root, it reads /proc, the containers' cgroups, and
`docker ps` / `docker inspect`. It writes one JSON file to `/opt/griip-internal-messaging/host-metrics/metrics.json`,
which compose mounts read-only into the API at `/host-metrics` (`HOST_METRICS_FILE=/host-metrics/metrics.json`). It
changes nothing. When it stops, its figures stop within 3 minutes, and the `collector.down` alert rule says so. The
API never gets the Docker socket.

Install (on the VPS, as root):

```
install -m 0755 deploy/scripts/host-metrics.py /opt/griip-internal-messaging/scripts/host-metrics.py
install -m 0644 deploy/systemd/meldiq-host-metrics.* /etc/systemd/system/
mkdir -p /opt/griip-internal-messaging/host-metrics
systemctl daemon-reload && systemctl enable --now meldiq-host-metrics.timer
```

Remove: `systemctl disable --now meldiq-host-metrics.timer` and delete the two unit files.

## Not measured, and why

- **Database query latency:** SQLite runs in the API process, so its time is part of each request's latency.
- **Cache:** there is none.
- **MongoDB pool:** MongoDB isn't used in production.
- **File storage provider metrics and bill:** reading them needs AWS CloudWatch and billing access; the server's key
  reads and writes only its bucket.
- **LiveKit internal (SFU) metrics:** LiveKit's metrics endpoint isn't enabled. Calls are measured from the server's
  call records; LiveKit's CPU, memory and network come from the host collector.
- **Screen sharing:** it happens inside LiveKit media and isn't recorded.
- **Hosting provider metrics and invoices:** these need a Hostinger API token on the server. Operators can enter the
  plan's price and transfer allowance in Settings for estimates.

## Bandwidth and costs

Measured once each, never added together:

- the host interface (the collector);
- each Meldiq service's network namespace (the collector, and the API's own);
- per-person API, WebSocket and file bytes (telemetry).

Most file transfer goes directly between apps and S3 (presigned links), so it's billed by AWS, not the VPS. Costs in
the console are estimates from operator-entered prices, with their source, and never invoices.

## Capacity projections

Disk: a linear trend (least squares) over the last 7 days of hourly averages. It's labelled an estimate and only shown
with at least 24 hours of history.
