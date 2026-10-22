/**
 * A Griip core node (docs/FEDERATION_PROTOCOL.md §3, docs/SERVER_LIFECYCLE.md). Core nodes keep the replicated
 * server registry: verified descriptors, domain bindings, reachability leases and final records (decommission,
 * revocation). They never see messages, contacts or user keys. Several independently operated nodes replicate
 * self-authenticating records; resolvers require a quorum of them to agree.
 */
import Database from "better-sqlite3";
import fs from "node:fs";
import path from "node:path";
import { chainExtends, type DescriptorCheck, isDomain, isServerId, type ServerDescriptor, type SelfSigned, verifyDescriptor, verifySelfSigned } from "../identity.js";
import {
  type CoreAnswer,
  coreIdFor,
  type Decommission,
  type FinalRecord,
  type LifecycleState,
  type Revocation,
  type ServerAnswer,
  signAnswer,
  verifyDecommission,
  verifyRevocation,
} from "../records.js";
import { generateKeyPair, type KeyPair, randomToken } from "../sign.js";

export interface CoreConfig {
  dataDir: string;
  databasePath?: string; // default dataDir/core.sqlite
  peers: { url: string; publicKey: string }[];
  /** Core keys whose signatures count towards a revocation (normally every core node, including this one). */
  trustedCoreKeys: string[];
  revocationThreshold: number;
  /** Development only: accept http:// endpoints and endpoints whose host differs from the domain. */
  allowHttp: boolean;
  leaseMs: number;
  /** How long a server must be unreachable before another server may take over its domain name. */
  domainReleaseMs: number;
  registration: "open" | "allowlist";
  allowlist: string[];
  fetchTimeoutMs: number;
}

export class CoreError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

interface StatusRow {
  server_id: string;
  state: LifecycleState;
  lease_until: number;
  unreachable_since: number | null;
  suspended: number;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS descriptors (server_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, doc TEXT NOT NULL, verified INTEGER NOT NULL, first_seen INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS domains (domain TEXT PRIMARY KEY, server_id TEXT NOT NULL, bound_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS status (server_id TEXT PRIMARY KEY, state TEXT NOT NULL, lease_until INTEGER NOT NULL, unreachable_since INTEGER, suspended INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS finals (server_id TEXT PRIMARY KEY, kind TEXT NOT NULL, doc TEXT NOT NULL, at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS feed (seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, server_id TEXT NOT NULL, doc TEXT NOT NULL, at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS cursors (peer TEXT PRIMARY KEY, after INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, action TEXT NOT NULL, server_id TEXT, detail TEXT);
`;

export class CoreNode {
  readonly key: KeyPair;
  readonly coreId: string;
  readonly db: Database.Database;
  private trusted: Set<string>;

  constructor(
    public cfg: CoreConfig,
    private fetchFn: typeof fetch = fetch,
  ) {
    fs.mkdirSync(cfg.dataDir, { recursive: true, mode: 0o700 });
    const keyFile = path.join(cfg.dataDir, "core-key.json");
    if (!fs.existsSync(keyFile)) fs.writeFileSync(keyFile, JSON.stringify(generateKeyPair()), { mode: 0o600, flag: "wx" });
    this.key = JSON.parse(fs.readFileSync(keyFile, "utf8")) as KeyPair;
    this.coreId = coreIdFor(this.key.publicKey);
    this.db = new Database(cfg.databasePath ?? path.join(cfg.dataDir, "core.sqlite"));
    this.db.pragma("journal_mode = WAL");
    this.db.exec(SCHEMA);
    this.trusted = new Set([...cfg.trustedCoreKeys, this.key.publicKey]);
  }

  close() {
    this.db.close();
  }

  private audit(action: string, serverId: string | null, detail?: string) {
    this.db.prepare("INSERT INTO audit (at, action, server_id, detail) VALUES (?, ?, ?, ?)").run(Date.now(), action, serverId, detail ?? null);
  }

  private stored(serverId: string): { seq: number; doc: ServerDescriptor; verified: boolean } | null {
    const r = this.db.prepare("SELECT seq, doc, verified FROM descriptors WHERE server_id = ?").get(serverId) as
      | { seq: number; doc: string; verified: number }
      | undefined;
    return r ? { seq: r.seq, doc: JSON.parse(r.doc), verified: !!r.verified } : null;
  }

  final(serverId: string): FinalRecord | null {
    const r = this.db.prepare("SELECT kind, doc FROM finals WHERE server_id = ?").get(serverId) as { kind: string; doc: string } | undefined;
    return r ? ({ kind: r.kind, record: JSON.parse(r.doc) } as FinalRecord) : null;
  }

  private status(serverId: string): StatusRow | undefined {
    return this.db.prepare("SELECT * FROM status WHERE server_id = ?").get(serverId) as StatusRow | undefined;
  }

  /** Production: https only, and the endpoint's host must be the domain it claims. */
  private checkEndpoint(d: ServerDescriptor) {
    let u: URL;
    try {
      u = new URL(d.endpoint);
    } catch {
      throw new CoreError(400, "bad_endpoint", "Endpoint isn't a URL");
    }
    if (this.cfg.allowHttp) return;
    if (u.protocol !== "https:") throw new CoreError(400, "insecure_endpoint", "Endpoints must use https");
    if (u.host !== d.domain) throw new CoreError(400, "domain_mismatch", "The endpoint must be served from the domain it claims");
  }

  /**
   * Proves that whoever answers at the endpoint holds the server's current key: the server signs a fresh nonce
   * naming this core node. Returns the descriptor the endpoint serves (it may be newer than the one presented).
   */
  async challenge(serverId: string, endpoint: string): Promise<ServerDescriptor> {
    const nonce = randomToken(18);
    const url = `${endpoint.replace(/\/$/, "")}/.well-known/griip-server?challenge=${encodeURIComponent(nonce)}&core=${this.coreId}`;
    let body: { descriptor: ServerDescriptor; proof: SelfSigned & { t: string; coreId: string; nonce: string } };
    try {
      const res = await this.fetchFn(url, { signal: AbortSignal.timeout(this.cfg.fetchTimeoutMs) });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      body = (await res.json()) as typeof body;
    } catch (e) {
      throw new CoreError(502, "endpoint_unreachable", `Couldn't reach the server's endpoint (${(e as Error).message})`);
    }
    const d = body?.descriptor;
    const check = d ? verifyDescriptor(d) : ({ ok: false, error: "no descriptor" } as DescriptorCheck);
    if (!check.ok || d.serverId !== serverId) throw new CoreError(400, "challenge_failed", "The endpoint serves a different or invalid server identity");
    const p = body.proof;
    if (!p || p.t !== "griip.server.challenge" || p.nonce !== nonce || p.coreId !== this.coreId || p.serverId !== serverId) {
      throw new CoreError(400, "challenge_failed", "Challenge proof doesn't match");
    }
    if (!verifySelfSigned(p) || !chainExtends(d.keyChain, p.keyChain)) throw new CoreError(400, "challenge_failed", "Challenge proof isn't signed by the server's key");
    return d;
  }

  /** Stores a descriptor whose endpoint this node verified, binding its domain if the binding rules allow. */
  private applyVerified(d: ServerDescriptor, now: number) {
    const existing = this.db.prepare("SELECT server_id FROM domains WHERE domain = ?").get(d.domain) as { server_id: string } | undefined;
    if (existing && existing.server_id !== d.serverId) {
      const other = this.status(existing.server_id);
      const released =
        !!this.final(existing.server_id) ||
        (other?.state === "unreachable" && other.unreachable_since !== null && now - other.unreachable_since > this.cfg.domainReleaseMs);
      if (!released) {
        this.audit("domain_conflict", d.serverId, `${d.domain} is bound to ${existing.server_id}`);
        throw new CoreError(409, "domain_taken", "That domain belongs to another registered server");
      }
      this.audit("domain_rebound", d.serverId, `${d.domain} from ${existing.server_id}`);
    }
    const prev = this.stored(d.serverId);
    this.db.transaction(() => {
      if (prev && prev.doc.domain !== d.domain) this.db.prepare("DELETE FROM domains WHERE domain = ? AND server_id = ?").run(prev.doc.domain, d.serverId);
      this.db
        .prepare(
          `INSERT INTO descriptors (server_id, seq, doc, verified, first_seen, updated_at) VALUES (?, ?, ?, 1, ?, ?)
           ON CONFLICT(server_id) DO UPDATE SET seq = excluded.seq, doc = excluded.doc, verified = 1, updated_at = excluded.updated_at`,
        )
        .run(d.serverId, d.seq, JSON.stringify(d), now, now);
      this.db.prepare("INSERT INTO domains (domain, server_id, bound_at) VALUES (?, ?, ?) ON CONFLICT(domain) DO UPDATE SET server_id = excluded.server_id, bound_at = excluded.bound_at").run(d.domain, d.serverId, now);
      this.db
        .prepare(
          `INSERT INTO status (server_id, state, lease_until, unreachable_since) VALUES (?, 'active', ?, NULL)
           ON CONFLICT(server_id) DO UPDATE SET state = 'active', lease_until = excluded.lease_until, unreachable_since = NULL`,
        )
        .run(d.serverId, now + this.cfg.leaseMs);
      if (!prev || prev.seq !== d.seq) this.db.prepare("INSERT INTO feed (kind, server_id, doc, at) VALUES ('descriptor', ?, ?, ?)").run(d.serverId, JSON.stringify(d), now);
    })();
  }

  /** Common checks for a descriptor arriving by registration or replication. Returns false if it's simply not newer. */
  private admissible(d: ServerDescriptor): boolean {
    const check = verifyDescriptor(d);
    if (!check.ok) throw new CoreError(400, "bad_descriptor", check.error);
    if (this.final(d.serverId)) throw new CoreError(410, "server_final", "This server was decommissioned or revoked");
    const prev = this.stored(d.serverId);
    if (prev) {
      if (!chainExtends(prev.doc.keyChain, d.keyChain)) throw new CoreError(409, "key_rollback", "Signed with a key this server has rotated away from");
      if (d.seq < prev.seq) throw new CoreError(409, "stale_descriptor", "A newer descriptor is already registered");
      if (d.seq === prev.seq && prev.verified) return false;
    }
    return true;
  }

  /** Join (or update): verify the descriptor, check policy, challenge the endpoint, then bind and activate. */
  async register(d: ServerDescriptor, now = Date.now()): Promise<ServerAnswer> {
    const newer = this.admissible(d);
    if (this.cfg.registration === "allowlist" && !this.cfg.allowlist.includes(d.serverId) && !this.cfg.allowlist.includes(d.domain)) {
      this.audit("register_refused", d.serverId, "not on allowlist");
      throw new CoreError(403, "not_allowed", "This network only admits listed servers");
    }
    this.checkEndpoint(d);
    const served = await this.challenge(d.serverId, d.endpoint);
    const best = served.seq > d.seq && chainExtends(d.keyChain, served.keyChain) ? served : d;
    if (newer || best !== d) this.applyVerified(best, now);
    else this.renew(d.serverId, now);
    this.audit("registered", d.serverId, `${best.domain} seq ${best.seq}`);
    return this.answerServer(d.serverId, now);
  }

  private renew(serverId: string, now: number) {
    this.db.prepare("UPDATE status SET state = 'active', lease_until = ?, unreachable_since = NULL WHERE server_id = ?").run(now + this.cfg.leaseMs, serverId);
  }

  /** A signed heartbeat renews the lease; a server coming back from "unreachable" is challenged again first. */
  async heartbeat(h: SelfSigned & { t: string; at: number; seq: number }, now = Date.now()): Promise<{ state: string; leaseUntil: number; wantDescriptor: boolean }> {
    if (!h || h.t !== "griip.server.heartbeat" || !isServerId(h.serverId)) throw new CoreError(400, "bad_heartbeat", "Not a heartbeat");
    if (this.final(h.serverId)) throw new CoreError(410, "server_final", "This server was decommissioned or revoked");
    const prev = this.stored(h.serverId);
    if (!prev) throw new CoreError(404, "unknown_server", "Register first");
    if (!verifySelfSigned(h) || !chainExtends(prev.doc.keyChain, h.keyChain)) throw new CoreError(401, "bad_signature", "Heartbeat isn't signed by the server's current key");
    if (Math.abs(now - h.at) > 5 * 60_000) throw new CoreError(400, "stale", "Heartbeat time is off");
    const st = this.status(h.serverId);
    if (!prev.verified || st?.state !== "active") {
      const served = await this.challenge(h.serverId, prev.doc.endpoint); // revalidate identity and endpoint before routing again
      if (served.seq > prev.seq && chainExtends(prev.doc.keyChain, served.keyChain)) this.applyVerified(served, now);
      else this.applyVerified(prev.doc, now);
      this.audit("revalidated", h.serverId);
    } else {
      this.renew(h.serverId, now);
    }
    const leaseUntil = (this.status(h.serverId) as StatusRow).lease_until;
    return { state: "active", leaseUntil, wantDescriptor: h.seq > prev.seq };
  }

  private finalize(serverId: string, kind: "decommissioned" | "revoked", doc: object, now: number): boolean {
    if (this.final(serverId)) return false;
    this.db.transaction(() => {
      this.db.prepare("INSERT INTO finals (server_id, kind, doc, at) VALUES (?, ?, ?, ?)").run(serverId, kind, JSON.stringify(doc), now);
      this.db.prepare("INSERT INTO status (server_id, state, lease_until) VALUES (?, ?, 0) ON CONFLICT(server_id) DO UPDATE SET state = excluded.state").run(serverId, kind);
      this.db.prepare("INSERT INTO feed (kind, server_id, doc, at) VALUES (?, ?, ?, ?)").run(kind === "decommissioned" ? "decommission" : "revocation", serverId, JSON.stringify(doc), now);
    })();
    this.audit(kind, serverId);
    return true;
  }

  /** Permanent, server-signed retirement. The id stays tombstoned forever so nobody can re-register it. */
  decommission(r: Decommission, now = Date.now()): ServerAnswer {
    const serverId = verifyDecommission(r);
    if (!serverId) throw new CoreError(400, "bad_record", "Decommission record doesn't verify");
    const prev = this.stored(serverId);
    if (prev && !chainExtends(prev.doc.keyChain, r.keyChain)) throw new CoreError(409, "key_rollback", "Signed with a rotated-away key");
    this.finalize(serverId, "decommissioned", r, now);
    return this.answerServer(serverId, now);
  }

  /** Network-level removal (e.g. a malicious server): needs `revocationThreshold` core signatures. */
  revoke(r: Revocation, now = Date.now()): ServerAnswer {
    if (!verifyRevocation(r, this.trusted, this.cfg.revocationThreshold)) throw new CoreError(400, "bad_record", "Revocation lacks enough valid core signatures");
    this.finalize(r.serverId, "revoked", r, now);
    return this.answerServer(r.serverId, now);
  }

  /** Local operator action on this node only (not replicated): the node reports the server as suspended. */
  setSuspended(serverId: string, on: boolean) {
    this.db.prepare("UPDATE status SET suspended = ? WHERE server_id = ?").run(on ? 1 : 0, serverId);
    this.audit(on ? "suspended" : "unsuspended", serverId);
  }

  /** Active servers whose lease ran out become unreachable. Nothing is ever deleted. */
  sweep(now = Date.now()): number {
    return this.db.prepare("UPDATE status SET state = 'unreachable', unreachable_since = ? WHERE state = 'active' AND lease_until < ?").run(now, now).changes;
  }

  answerServer(serverId: string, now = Date.now()): ServerAnswer {
    const final = this.final(serverId);
    const d = this.stored(serverId);
    const st = this.status(serverId);
    let state: ServerAnswer["state"] = "unknown";
    if (final) state = final.kind;
    else if (st?.suspended) state = "suspended";
    else if (d && !d.verified) state = "pending";
    else if (st) state = st.state === "active" && st.lease_until < now ? "unreachable" : st.state;
    return { serverId, domain: d?.doc.domain ?? null, state, descriptor: d?.doc ?? null, final, leaseUntil: st?.lease_until ?? null };
  }

  answerDomain(domain: string, now = Date.now()): ServerAnswer {
    const b = this.db.prepare("SELECT server_id FROM domains WHERE domain = ?").get(domain) as { server_id: string } | undefined;
    if (!b) return { serverId: null, domain, state: "unknown", descriptor: null, final: null, leaseUntil: null };
    return { ...this.answerServer(b.server_id, now), domain };
  }

  signed(query: string, result: ServerAnswer): CoreAnswer {
    return signAnswer(this.key, query, result);
  }

  feed(after: number, limit: number) {
    return (this.db.prepare("SELECT seq, kind, doc FROM feed WHERE seq > ? ORDER BY seq LIMIT ?").all(after, limit) as { seq: number; kind: string; doc: string }[]).map((r) => ({
      seq: r.seq,
      kind: r.kind,
      doc: JSON.parse(r.doc),
    }));
  }

  /**
   * Pull replication from every peer. Records verify on their own; descriptors are additionally re-challenged by
   * this node before it binds their domain, so a peer can't make this node vouch for an endpoint it didn't check.
   */
  async replicateOnce(now = Date.now()): Promise<{ peer: string; applied: number; error?: string }[]> {
    const out = [];
    for (const p of this.cfg.peers) {
      let applied = 0;
      try {
        const cur = (this.db.prepare("SELECT after FROM cursors WHERE peer = ?").get(p.publicKey) as { after: number } | undefined)?.after ?? 0;
        const res = await this.fetchFn(`${p.url.replace(/\/$/, "")}/core/v1/feed?after=${cur}&limit=200`, { signal: AbortSignal.timeout(this.cfg.fetchTimeoutMs) });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const body = (await res.json()) as { records: { seq: number; kind: string; doc: any }[] };
        let max = cur;
        for (const r of body.records ?? []) {
          max = Math.max(max, r.seq);
          try {
            if (await this.applyReplicated(r.kind, r.doc, now)) applied++;
          } catch (e) {
            this.audit("replication_rejected", r.doc?.serverId ?? null, `${r.kind}: ${(e as Error).message}`);
          }
        }
        this.db.prepare("INSERT INTO cursors (peer, after) VALUES (?, ?) ON CONFLICT(peer) DO UPDATE SET after = excluded.after").run(p.publicKey, max);
        out.push({ peer: p.url, applied });
      } catch (e) {
        out.push({ peer: p.url, applied, error: (e as Error).message });
      }
    }
    return out;
  }

  private async applyReplicated(kind: string, doc: any, now: number): Promise<boolean> {
    if (kind === "decommission") {
      const id = verifyDecommission(doc);
      if (!id) throw new Error("bad decommission");
      const prev = this.stored(id);
      if (prev && !chainExtends(prev.doc.keyChain, doc.keyChain)) throw new Error("decommission signed with an old key");
      return this.finalize(id, "decommissioned", doc, now);
    }
    if (kind === "revocation") {
      if (!verifyRevocation(doc, this.trusted, this.cfg.revocationThreshold)) throw new Error("bad revocation");
      return this.finalize(doc.serverId, "revoked", doc, now);
    }
    if (kind === "descriptor") {
      const d = doc as ServerDescriptor;
      if (!this.admissible(d)) return false;
      this.checkEndpoint(d);
      try {
        const served = await this.challenge(d.serverId, d.endpoint);
        this.applyVerified(served.seq >= d.seq && chainExtends(d.keyChain, served.keyChain) ? served : d, now);
      } catch (e) {
        if (e instanceof CoreError && e.code === "domain_taken") throw e;
        // Unreachable from here right now: keep the (self-authenticating) descriptor as pending, without a domain binding.
        const prev = this.stored(d.serverId);
        if (!prev || prev.seq < d.seq) {
          this.db
            .prepare(
              `INSERT INTO descriptors (server_id, seq, doc, verified, first_seen, updated_at) VALUES (?, ?, ?, 0, ?, ?)
               ON CONFLICT(server_id) DO UPDATE SET seq = excluded.seq, doc = excluded.doc, verified = 0, updated_at = excluded.updated_at`,
            )
            .run(d.serverId, d.seq, JSON.stringify(d), now, now);
        }
      }
      return true;
    }
    return false;
  }

  auditLog(limit = 100) {
    return this.db.prepare("SELECT at, action, server_id AS serverId, detail FROM audit ORDER BY id DESC LIMIT ?").all(limit);
  }
}

export const validDomainParam = (d: string) => isDomain(d);
