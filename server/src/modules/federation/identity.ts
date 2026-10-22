/**
 * Stable server identity (docs/GLOBAL_IDENTITIES_AND_DISCOVERY.md §2).
 *
 * - A server generates an Ed25519 *root key* once. Its id is self-certifying:
 *   serverId = "gs1" + base32(sha256(rootKey))[0..26]. Nobody can map that id to another key.
 * - Operational keys rotate through a chain: each new key is signed by the previous one (the first by the root).
 * - The server publishes a signed *descriptor* (id, keys, domain, endpoint, protocols, capabilities, seq, expiry).
 *   A higher `seq` replaces a lower one; a lower one is a rollback and is ignored.
 * - Domain names and URLs are routing hints bound to the id by the signed descriptor and the core nodes' endpoint
 *   challenge; changing them never changes the id.
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { base32, generateKeyPair, type KeyPair, signRecord, verifyRecord } from "./sign.js";

export const FED_PROTOCOL = "griip-fed/1";
const DESCRIPTOR_TTL_MS = 7 * 24 * 3600_000;

export interface KeyLink {
  index: number; // 1, 2, … (index 0 is the root key itself)
  key: string;
  createdAt: number;
  sig: string; // by the key at index-1 over {t:"griip.server.key", serverId, index, key, createdAt}
}

export interface ServerDescriptor {
  v: 1;
  t: "griip.server.descriptor";
  serverId: string;
  rootKey: string;
  keyChain: KeyLink[];
  domain: string;
  endpoint: string;
  protocols: string[];
  capabilities: string[];
  seq: number;
  issuedAt: number;
  expiresAt: number;
  sig: string;
}

export function serverIdFor(rootKey: string): string {
  return "gs1" + base32(crypto.createHash("sha256").update(Buffer.from(rootKey, "base64")).digest()).slice(0, 26);
}

const SERVER_ID = /^gs1[a-z2-7]{26}$/;
export const isServerId = (s: unknown): s is string => typeof s === "string" && SERVER_ID.test(s);

/** host or host:port, lowercase; no scheme, path or user info. */
const DOMAIN = /^(?=.{1,253}(:\d{1,5})?$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*(:\d{1,5})?$/;
export const isDomain = (s: unknown): s is string => typeof s === "string" && DOMAIN.test(s);

function linkBody(serverId: string, l: Omit<KeyLink, "sig">) {
  return { t: "griip.server.key", serverId, index: l.index, key: l.key, createdAt: l.createdAt };
}

/** Walks the key chain from the root; returns the current key, or null if any link is broken. */
export function currentKeyOf(serverId: string, rootKey: string, chain: KeyLink[]): string | null {
  if (serverIdFor(rootKey) !== serverId) return null;
  let key = rootKey;
  for (let i = 0; i < chain.length; i++) {
    const l = chain[i];
    if (!l || l.index !== i + 1 || typeof l.key !== "string") return null;
    if (!verifyRecord(key, { ...linkBody(serverId, l), sig: l.sig })) return null;
    key = l.key;
  }
  return key;
}

export type DescriptorCheck = { ok: true; currentKey: string } | { ok: false; error: string };

/** Full verification of a descriptor that came from anywhere (a peer, a core node, a cache). */
export function verifyDescriptor(d: ServerDescriptor, now = Date.now(), opts: { allowExpired?: boolean } = {}): DescriptorCheck {
  if (!d || d.v !== 1 || d.t !== "griip.server.descriptor") return { ok: false, error: "not a descriptor" };
  if (!isServerId(d.serverId)) return { ok: false, error: "bad server id" };
  if (!isDomain(d.domain)) return { ok: false, error: "bad domain" };
  if (typeof d.endpoint !== "string" || !/^https?:\/\/[^\s/?#]+(\/[^\s?#]*)?$/.test(d.endpoint)) return { ok: false, error: "bad endpoint" };
  if (!Number.isSafeInteger(d.seq) || d.seq < 1) return { ok: false, error: "bad seq" };
  if (!Array.isArray(d.keyChain) || d.keyChain.length > 64) return { ok: false, error: "bad key chain" };
  if (!Array.isArray(d.protocols) || !d.protocols.includes(FED_PROTOCOL)) return { ok: false, error: "unsupported protocol" };
  if (!Array.isArray(d.capabilities)) return { ok: false, error: "bad capabilities" };
  if (!opts.allowExpired && !(d.expiresAt > now)) return { ok: false, error: "descriptor expired" };
  if (d.issuedAt > now + 5 * 60_000) return { ok: false, error: "descriptor from the future" };
  const key = currentKeyOf(d.serverId, d.rootKey, d.keyChain);
  if (!key) return { ok: false, error: "server id doesn't match its key chain" };
  if (!verifyRecord(key, d)) return { ok: false, error: "bad descriptor signature" };
  return { ok: true, currentKey: key };
}

/**
 * True when `presented` is the same chain as `known` or a longer one with `known` as its prefix. A shorter or
 * diverging chain means someone is signing with a rotated-away key: rejected everywhere (core nodes, resolvers,
 * request verification), so an old or stolen key can't roll a server back.
 */
export function chainExtends(known: KeyLink[], presented: KeyLink[]): boolean {
  if (presented.length < known.length) return false;
  for (let i = 0; i < known.length; i++) {
    if (known[i].key !== presented[i].key || known[i].sig !== presented[i].sig) return false;
  }
  return true;
}

/** A record a server signs with its current key (decommission, heartbeat, challenge proof, request). */
export interface SelfSigned {
  serverId: string;
  rootKey: string;
  keyChain: KeyLink[];
  sig: string;
}

export function verifySelfSigned(r: SelfSigned): string | null {
  if (!r || !isServerId(r.serverId) || !Array.isArray(r.keyChain)) return null;
  const key = currentKeyOf(r.serverId, r.rootKey, r.keyChain);
  return key && verifyRecord(key, r) ? key : null;
}

interface KeyFile {
  v: 1;
  root: KeyPair;
  chain: (KeyLink & { privateKey: string })[];
  seq: number;
}

/**
 * The server's own identity, persisted in `DATA_DIR/federation/server-key.json` (mode 600). Created once with
 * O_EXCL and never regenerated or overwritten except to append a rotated key.
 */
export class ServerIdentity {
  private constructor(
    private file: string,
    private data: KeyFile,
  ) {}

  static loadOrCreate(dir: string): ServerIdentity {
    const file = path.join(dir, "server-key.json");
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    if (!fs.existsSync(file)) {
      const data: KeyFile = { v: 1, root: generateKeyPair(), chain: [], seq: 0 };
      fs.writeFileSync(file, JSON.stringify(data), { mode: 0o600, flag: "wx" }); // never clobbers an existing identity
    }
    const data = JSON.parse(fs.readFileSync(file, "utf8")) as KeyFile;
    const id = new ServerIdentity(file, data);
    if (!currentKeyOf(id.serverId, data.root.publicKey, id.publicChain())) throw new Error(`server identity in ${file} is corrupt`);
    return id;
  }

  get serverId(): string {
    return serverIdFor(this.data.root.publicKey);
  }
  get rootKey(): string {
    return this.data.root.publicKey;
  }
  publicChain(): KeyLink[] {
    return this.data.chain.map(({ privateKey: _p, ...l }) => l);
  }
  private get current(): KeyPair {
    const last = this.data.chain.at(-1);
    return last ? { publicKey: last.key, privateKey: last.privateKey } : this.data.root;
  }
  get currentKey(): string {
    return this.current.publicKey;
  }

  private save() {
    const tmp = this.file + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify(this.data), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }

  /** Next descriptor sequence number; persisted so a restart never reuses (or rolls back) a seq. */
  private nextSeq(): number {
    this.data.seq = Math.max(this.data.seq + 1, Date.now());
    this.save();
    return this.data.seq;
  }

  /** Adds a new operational key signed by the current one. The server id stays the same. */
  rotateKey(now = Date.now()): string {
    const kp = generateKeyPair();
    const index = this.data.chain.length + 1;
    const link = signRecord(this.current, linkBody(this.serverId, { index, key: kp.publicKey, createdAt: now }));
    this.data.chain.push({ index, key: kp.publicKey, createdAt: now, sig: link.sig, privateKey: kp.privateKey });
    this.save();
    return kp.publicKey;
  }

  descriptor(p: { domain: string; endpoint: string; capabilities: string[] }, now = Date.now()): ServerDescriptor {
    return signRecord(this.current, {
      v: 1 as const,
      t: "griip.server.descriptor" as const,
      serverId: this.serverId,
      rootKey: this.rootKey,
      keyChain: this.publicChain(),
      domain: p.domain,
      endpoint: p.endpoint,
      protocols: [FED_PROTOCOL],
      capabilities: p.capabilities,
      seq: this.nextSeq(),
      issuedAt: now,
      expiresAt: now + DESCRIPTOR_TTL_MS,
    }) as ServerDescriptor;
  }

  /** Signs `body` together with this server's id and key chain, so the result verifies on its own (`verifySelfSigned`). */
  selfSigned<T extends object>(body: T): T & SelfSigned {
    return signRecord(this.current, { ...body, serverId: this.serverId, rootKey: this.rootKey, keyChain: this.publicChain() }) as T & SelfSigned;
  }

  /** Raw signature with the current key, for signed HTTP requests. */
  signText(message: string): string {
    return signRecord(this.current, { m: message }).sig;
  }
}

/** Verifies a `signText` signature from a server whose current key is `key`. */
export function verifyServerText(key: string, message: string, sig: string): boolean {
  return verifyRecord(key, { m: message, sig });
}
