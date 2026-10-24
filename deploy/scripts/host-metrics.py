#!/usr/bin/env python3
"""
Meldiq host collector (docs/RESOURCE_MONITORING.md): run by a systemd timer on the VPS, as root, every 30 seconds.

Writes one JSON file the API reads through a read-only mount (HOST_METRICS_FILE): the host's network interfaces and,
for each Meldiq container (compose project griip-internal-messaging: api, livekit, ntfy), its cumulative CPU time,
memory, network and block I/O. Every other workload on this shared host is summed into one "other" group: no other
project's names, ids or figures leave this script.

Counters are cumulative (the API computes rates). Standard library only; reads /proc, /sys/fs/cgroup and `docker`.
Nothing here changes any container.
"""
import json
import os
import platform
import socket
import subprocess
import sys
import tempfile
import time

PROJECT = "griip-internal-messaging"
SERVICES = {"api", "livekit", "ntfy"}
OUT = os.environ.get("HOST_METRICS_OUT", "/opt/griip-internal-messaging/host-metrics/metrics.json")
SKIP_IFACE = ("lo", "veth", "docker", "br-")


def net_dev(path):
    out = {}
    try:
        with open(path) as f:
            lines = f.read().splitlines()[2:]
    except OSError:
        return out
    for line in lines:
        name, _, rest = line.partition(":")
        name = name.strip()
        if not rest or name.startswith(SKIP_IFACE):
            continue
        v = [int(x) for x in rest.split()]
        out[name] = {"rx": v[0], "rxErrors": v[2], "rxDrops": v[3], "tx": v[8], "txErrors": v[10], "txDrops": v[11]}
    return out


def read_int(path):
    try:
        with open(path) as f:
            s = f.read().strip()
        return None if s == "max" else int(s)
    except (OSError, ValueError):
        return None


def cgroup_dir(full_id):
    for d in (f"/sys/fs/cgroup/system.slice/docker-{full_id}.scope", f"/sys/fs/cgroup/docker/{full_id}"):
        if os.path.isdir(d):
            return d
    return None


def cgroup_stats(d):
    usec, rbytes, wbytes = 0, 0, 0
    try:
        with open(f"{d}/cpu.stat") as f:
            for line in f:
                if line.startswith("usage_usec "):
                    usec = int(line.split()[1])
    except OSError:
        pass
    try:
        with open(f"{d}/io.stat") as f:
            for line in f:
                for part in line.split()[1:]:
                    k, _, v = part.partition("=")
                    if k == "rbytes":
                        rbytes += int(v)
                    elif k == "wbytes":
                        wbytes += int(v)
    except OSError:
        pass
    return usec, read_int(f"{d}/memory.current") or 0, read_int(f"{d}/memory.max"), rbytes, wbytes


def containers():
    ids = subprocess.run(["docker", "ps", "-q", "--no-trunc"], capture_output=True, text=True, timeout=20).stdout.split()
    if not ids:
        return []
    fmt = '{{.Id}}|{{.State.Pid}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}'
    rows = subprocess.run(["docker", "inspect", "--format", fmt, *ids], capture_output=True, text=True, timeout=20).stdout.splitlines()
    groups = {}
    for row in rows:
        full_id, pid, project, service = (row.split("|") + ["", "", "", ""])[:4]
        group = service if project == PROJECT and service in SERVICES else "other"
        d = cgroup_dir(full_id)
        if not d:
            continue
        usec, mem, mem_max, rb, wb = cgroup_stats(d)
        nets = net_dev(f"/proc/{pid}/net/dev") if pid and pid != "0" else {}
        g = groups.setdefault(group, {"group": group, "cpuUsec": 0, "memBytes": 0, "memLimit": None, "netRx": 0, "netTx": 0, "ioRead": 0, "ioWrite": 0, "count": 0})
        g["cpuUsec"] += usec
        g["memBytes"] += mem
        g["netRx"] += sum(i["rx"] for i in nets.values())
        g["netTx"] += sum(i["tx"] for i in nets.values())
        g["ioRead"] += rb
        g["ioWrite"] += wb
        g["count"] += 1
        if group != "other" and mem_max:
            g["memLimit"] = mem_max
    return list(groups.values())


def os_name():
    try:
        with open("/etc/os-release") as f:
            for line in f:
                if line.startswith("PRETTY_NAME="):
                    return line.split("=", 1)[1].strip().strip('"')
    except OSError:
        pass
    return platform.system()


def main():
    try:
        conts = containers()
    except (OSError, subprocess.SubprocessError) as e:
        print(f"docker unavailable: {type(e).__name__}", file=sys.stderr)
        conts = []
    doc = {
        "version": 1,
        "at": int(time.time() * 1000),
        "hostname": socket.gethostname(),
        "os": os_name(),
        "kernel": platform.release(),
        "cpus": os.cpu_count(),
        "interfaces": net_dev("/proc/net/dev"),
        "containers": conts,
    }
    out_dir = os.path.dirname(OUT)
    os.makedirs(out_dir, mode=0o755, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=out_dir, prefix=".metrics-")
    with os.fdopen(fd, "w") as f:
        json.dump(doc, f)
    os.chmod(tmp, 0o644)
    os.replace(tmp, OUT)


if __name__ == "__main__":
    main()
