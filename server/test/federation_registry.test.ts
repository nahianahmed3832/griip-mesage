import fs from "node:fs";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { CoreError } from "../src/core/node.js";
import { chainExtends, currentKeyOf, serverIdFor, verifyDescriptor } from "../src/federation/identity.js";
import { signRevocation, verifyAnswer } from "../src/federation/records.js";
import { signRecord } from "../src/federation/sign.js";
import { fakeServer, startCores, stopCores, type TestCore, tempIdentity } from "./support/federation.js";

let cores: TestCore[];
const cleanup: (() => Promise<unknown> | void)[] = [];
beforeAll(async () => {
  cores = await startCores(3);
});
afterAll(async () => {
  for (const c of cleanup.reverse()) await c();
  await stopCores(cores);
});

async function server(domain: string) {
  const { identity, dir } = tempIdentity();
  const s = await fakeServer(identity, domain);
  cleanup.push(() => s.close(), () => fs.rmSync(dir, { recursive: true, force: true }));
  return s;
}

const code = async (p: Promise<unknown> | (() => unknown)) => {
  try {
    await (typeof p === "function" ? p() : p);
  } catch (e) {
    if (e instanceof CoreError) return e.code;
    throw e;
  }
  return "ok";
};

describe("server identity", () => {
  it("is self-certifying, survives key rotation and rejects tampering, expiry and rollback", async () => {
    const s = await server("id.test");
    const id = s.identity;
    expect(id.serverId).toBe(serverIdFor(id.rootKey));
    expect(id.serverId).toMatch(/^gs1[a-z2-7]{26}$/);
    const d1 = s.publish();
    expect(verifyDescriptor(d1).ok).toBe(true);
    const oldChain = d1.keyChain;
    id.rotateKey();
    const d2 = s.publish();
    expect(d2.serverId).toBe(d1.serverId); // rotation never changes the id
    expect(d2.seq).toBeGreaterThan(d1.seq);
    expect(currentKeyOf(d2.serverId, d2.rootKey, d2.keyChain)).toBe(id.currentKey);
    expect(chainExtends(d2.keyChain, oldChain)).toBe(false); // an old chain doesn't extend the new one
    expect(verifyDescriptor({ ...d2, domain: "evil.test" }).ok).toBe(false); // any field change breaks the signature
    expect(verifyDescriptor({ ...d2, rootKey: tempIdentity().identity.rootKey }).ok).toBe(false); // id ≠ key
    expect(verifyDescriptor(d2, d2.expiresAt + 1).ok).toBe(false); // expired
    const forged = { ...d2, keyChain: [{ ...d2.keyChain[0], key: tempIdentity().identity.rootKey }] };
    expect(verifyDescriptor(forged).ok).toBe(false); // a key not signed by the previous one
  });
});

describe("core registry", () => {
  it("admits a server only after it proves control of its endpoint, and binds its domain", async () => {
    const a = await server("a.test");
    const r = await cores[0].node.register(a.publish());
    expect(r.state).toBe("active");
    const ans = cores[0].node.signed("domain:a.test", cores[0].node.answerDomain("a.test"));
    expect(verifyAnswer(ans, cores[0].node.key.publicKey, "domain:a.test")).toBe(true);
    expect(ans.result.serverId).toBe(a.identity.serverId);
    expect(verifyAnswer({ ...ans, result: { ...ans.result, serverId: "gs1" + "a".repeat(26) } }, cores[0].node.key.publicKey, "domain:a.test")).toBe(false);
  });

  it("rejects spoofed identities: someone else's endpoint, someone else's domain, http/host mismatch in production", async () => {
    const victim = await server("victim.test");
    await cores[0].node.register(victim.publish());
    const attacker = await server("attacker.test");
    // Claims to live at the victim's endpoint: the victim's endpoint answers the challenge with the victim's id.
    expect(await code(cores[0].node.register(attacker.publish({ endpoint: victim.url })))).toBe("challenge_failed");
    // Claims the victim's domain from its own endpoint: the domain is already bound.
    expect(await code(cores[0].node.register(attacker.publish({ domain: "victim.test" })))).toBe("domain_taken");
    expect(cores[0].node.answerDomain("victim.test").serverId).toBe(victim.identity.serverId);
    // Production rules: https only, endpoint host = domain.
    cores[0].node.cfg.allowHttp = false;
    try {
      expect(await code(cores[0].node.register(attacker.publish({ domain: "attacker.test" })))).toBe("insecure_endpoint");
    } finally {
      cores[0].node.cfg.allowHttp = true;
    }
    expect(cores[0].node.auditLog().some((e: any) => e.action === "domain_conflict")).toBe(true);
  });

  it("rejects invalid, expired, stale and rolled-back descriptors", async () => {
    const s = await server("roll.test");
    const d1 = s.publish();
    await cores[0].node.register(d1);
    expect(await code(cores[0].node.register({ ...d1, sig: d1.sig.replace(/^./, d1.sig[0] === "A" ? "B" : "A") }))).toBe("bad_descriptor");
    expect(await code(cores[0].node.register(signRecordLike(s, { ...d1, expiresAt: Date.now() - 1 })))).toBe("bad_descriptor");
    const d2 = s.publish();
    await cores[0].node.register(d2);
    expect(await code(cores[0].node.register(d1))).toBe("stale_descriptor"); // older seq can't roll the registry back
    // Key rotated; someone who stole the OLD private key signs a newer-looking descriptor over the old chain.
    const oldKey = (s.identity as any).current;
    s.identity.rotateKey();
    const d3 = s.publish();
    await cores[0].node.register(d3);
    const { sig: _s, ...d2body } = d2;
    const oldKeyForgery = signRecord(oldKey, { ...d2body, seq: d3.seq + 1000, endpoint: "http://127.0.0.1:9" });
    expect(verifyDescriptor(oldKeyForgery as any).ok).toBe(true); // valid in isolation…
    expect(await code(cores[0].node.register(oldKeyForgery as any))).toBe("key_rollback"); // …but its chain is shorter than the known one
    const hb = s.identity.selfSigned({ t: "griip.server.heartbeat", at: Date.now(), seq: d3.seq });
    expect((await cores[0].node.heartbeat({ ...hb })).state).toBe("active");
    expect(await code(cores[0].node.heartbeat({ ...hb, keyChain: [] }))).toBe("bad_signature"); // truncated chain = old key
  });

  it("replicates registrations; each replica re-challenges before binding", async () => {
    const b = await server("b.test");
    await cores[0].node.register(b.publish());
    await cores[1].node.replicateOnce();
    await cores[2].node.replicateOnce();
    for (const c of cores) expect(c.node.answerDomain("b.test")).toMatchObject({ serverId: b.identity.serverId, state: "active" });
    // Replaying the feed is idempotent: no duplicate rows, nothing new applied.
    const again = await cores[1].node.replicateOnce();
    expect(again.every((r) => !r.error)).toBe(true);
    expect((cores[1].node.db.prepare("SELECT COUNT(*) n FROM descriptors WHERE server_id = ?").get(b.identity.serverId) as any).n).toBe(1);
  });

  it("marks a silent server unreachable without forgetting it, and revalidates it when it returns", async () => {
    const u = await server("u.test");
    const d = u.publish();
    await cores[0].node.register(d);
    const later = Date.now() + cores[0].node.cfg.leaseMs + 1000;
    expect(cores[0].node.sweep(later)).toBeGreaterThanOrEqual(1);
    const st = cores[0].node.answerServer(u.identity.serverId, later);
    expect(st.state).toBe("unreachable");
    expect(st.descriptor?.serverId).toBe(u.identity.serverId); // identity and registration kept
    const hb = u.identity.selfSigned({ t: "griip.server.heartbeat", at: Date.now(), seq: d.seq });
    expect((await cores[0].node.heartbeat(hb)).state).toBe("active"); // re-challenged, then active again
    expect((cores[0].node.db.prepare("SELECT COUNT(*) n FROM descriptors WHERE server_id = ?").get(u.identity.serverId) as any).n).toBe(1);
    expect(cores[0].node.auditLog().some((e: any) => e.action === "revalidated" && e.serverId === u.identity.serverId)).toBe(true);
  });

  it("decommissions permanently: replicated, never re-registrable, and the domain becomes free", async () => {
    const x = await server("x.test");
    await cores[0].node.register(x.publish());
    const rec = x.identity.selfSigned({ t: "griip.server.decommission" as const, reason: "closing", at: Date.now() });
    expect(cores[0].node.decommission(rec).state).toBe("decommissioned");
    await cores[1].node.replicateOnce();
    expect(cores[1].node.answerServer(x.identity.serverId).state).toBe("decommissioned");
    expect(await code(cores[0].node.register(x.publish()))).toBe("server_final");
    const forged = { ...rec, reason: "something else" };
    expect(await code(() => cores[2].node.decommission(forged))).toBe("bad_record");
    const successor = await server("x.test");
    expect((await cores[0].node.register(successor.publish())).state).toBe("active"); // new id for the domain; the old id stays tombstoned
    expect(cores[0].node.answerServer(x.identity.serverId).state).toBe("decommissioned");
  });

  it("revokes only with a threshold of core signatures, and replicates the revocation", async () => {
    const m = await server("m.test");
    await cores[0].node.register(m.publish());
    const body = { serverId: m.identity.serverId, reason: "abuse", at: Date.now() };
    const one = { t: "griip.server.revocation" as const, ...body, sigs: [signRevocation(cores[0].node.key, body)] };
    expect(await code(() => cores[0].node.revoke(one))).toBe("bad_record");
    const two = { ...one, sigs: [...one.sigs, signRevocation(cores[1].node.key, body)] };
    expect(cores[0].node.revoke(two).state).toBe("revoked");
    await cores[2].node.replicateOnce();
    expect(cores[2].node.answerServer(m.identity.serverId).state).toBe("revoked");
  });

  it("keeps answering while one core node is down, and catches up when it returns", async () => {
    const down = cores[2];
    await down.app.close(); // stop serving; its database stays
    const c = await server("c.test");
    await cores[0].node.register(c.publish());
    await cores[1].node.replicateOnce();
    expect(cores[1].node.answerDomain("c.test").serverId).toBe(c.identity.serverId);
    // The returning node pulls what it missed.
    await down.node.replicateOnce();
    expect(down.node.answerDomain("c.test").serverId).toBe(c.identity.serverId);
  });
});

/** Re-sign a modified descriptor with the server's real key (for expiry tests). */
function signRecordLike(s: Awaited<ReturnType<typeof fakeServer>>, d: any) {
  const kp = (s.identity as any).current;
  return signRecord(kp, d);
}
