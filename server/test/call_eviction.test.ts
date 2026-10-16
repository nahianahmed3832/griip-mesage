import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { runCleanup } from "../src/jobs.js";
import type { LiveKitService } from "../src/livekit.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** Stands in for the LiveKit server: tracks who is in each room and records every removal. */
class RecordingLiveKit {
  publicUrl = "ws://livekit.test";
  rooms = new Map<string, Set<string>>();
  removed: { room: string; identity: string }[] = [];
  unreachable = false;

  join(room: string, c: GriipClient) {
    if (!this.rooms.has(room)) this.rooms.set(room, new Set());
    this.rooms.get(room)!.add(`${c.userId}.${c.deviceId}`);
  }
  inRoom(room: string, c: GriipClient) {
    return this.rooms.get(room)?.has(`${c.userId}.${c.deviceId}`) ?? false;
  }
  async token(p: { identity: string }) {
    return `token:${p.identity}`;
  }
  async deleteRoom(room: string) {
    this.rooms.delete(room);
  }
  async participantIdentities(room: string) {
    return this.unreachable ? null : [...(this.rooms.get(room) ?? [])];
  }
  async participantUsers(room: string) {
    const ids = await this.participantIdentities(room);
    return ids && new Set(ids.map((i) => i.split(".")[0]));
  }
  async removeParticipant(room: string, identity: string) {
    if (this.unreachable) return "error" as const;
    if (!this.rooms.get(room)?.delete(identity)) return "absent" as const;
    this.removed.push({ room, identity });
    return "removed" as const;
  }
  async receiveWebhook(body: string) {
    return JSON.parse(body);
  }
}

let h: Harness;
let lk: RecordingLiveKit;

beforeAll(async () => {
  h = await startServer();
});
afterAll(async () => h.close());
beforeEach(() => {
  lk = new RecordingLiveKit();
  h.ctx.livekit = lk as unknown as LiveKitService;
});

/** Starts a call in a conversation; every client listed joins the room and confirms. */
async function callWith(convId: string, starter: GriipClient, ...others: GriipClient[]) {
  const r = await starter.req("POST", "/v1/calls", { conversationId: convId, video: true });
  for (const c of [starter, ...others]) {
    if (c !== starter) await c.req("POST", `/v1/calls/${r.call.id}/join`);
    lk.join(r.media.room, c);
    await c.req("POST", `/v1/calls/${r.call.id}/connected`);
  }
  return { id: r.call.id as string, room: r.media.room as string };
}

const participantIds = async (who: GriipClient, callId: string) =>
  (await who.req("GET", `/v1/calls/${callId}`)).call.participants.map((p: any) => p.userId);

describe("removing a member evicts them from live calls (T1.3)", () => {
  it("removal from a group: out of the room, marked left, told the call ended, can't rejoin; others carry on", async () => {
    const owner = await h.client("evowner");
    const a = await h.client("eva");
    const b = await h.client("evb");
    const group = (await owner.req("POST", "/v1/conversations/group", { name: "Ops", memberIds: [a.userId, b.userId] })).conversation.id;
    const call = await callWith(group, owner, a, b);
    await a.connect();
    await b.connect();

    await owner.req("DELETE", `/v1/conversations/${group}/members/${a.userId}`);

    expect(lk.removed).toEqual([{ room: call.room, identity: `${a.userId}.${a.deviceId}` }]);
    expect(await participantIds(owner, call.id)).toEqual(expect.arrayContaining([owner.userId, b.userId]));
    expect(await participantIds(owner, call.id)).not.toContain(a.userId);
    const after = await owner.req("GET", `/v1/calls/${call.id}`);
    expect(after.call.state).toBe("ACTIVE");
    await a.waitFor(() => a.events.find((e) => e.t === "call" && e.event === "ended" && e.call.id === call.id && e.call.endReason === "removed"));
    await b.waitFor(() => b.events.find((e) => e.t === "call" && e.event === "updated" && e.call.id === call.id && !e.call.participants.some((p: any) => p.userId === a.userId)));
    await expectStatus(a.req("POST", `/v1/calls/${call.id}/join`), 404);
    await expectStatus(a.req("POST", `/v1/calls/${call.id}/connected`), 404);
    await a.disconnect();
    await b.disconnect();
  });

  it("covers every org removal path: team, branch, demotion to Guest and leaving the org", async () => {
    const owner = await h.client("orgevowner");
    const org = (await owner.req("POST", "/v1/orgs", { name: "Evict Co" })).org.id;
    const branch = (await owner.req("POST", `/v1/orgs/${org}/branches`, { name: "Dhaka" })).branch.id;
    const team = (await owner.req("POST", `/v1/orgs/${org}/teams`, { name: "Ops", branchId: branch })).team.id;
    const join = async (name: string) => {
      const c = await h.client(name);
      const code = (await owner.req("POST", `/v1/orgs/${org}/invites`, { role: "MEMBER", teamId: team })).invite.code;
      await c.req("POST", "/v1/invites/accept", { code });
      return c;
    };
    const stays = await join("staysin");
    const viaTeam = await join("viateam");
    const viaBranch = await join("viabranch");
    const viaGuest = await join("viaguest");
    const viaLeave = await join("vialeave");
    const all = [stays, viaTeam, viaBranch, viaGuest, viaLeave];
    const teamChannel = (
      await owner.req("POST", `/v1/orgs/${org}/channels`, { name: "ops-private", scope: "TEAM", scopeId: team, kind: "PRIVATE", memberIds: all.map((c) => c.userId) })
    ).channel.id;
    const lobby = (await owner.req("POST", `/v1/orgs/${org}/channels`, { name: "lobby", scope: "ORG", kind: "PUBLIC" })).channel.id;
    for (const c of all) await c.req("POST", `/v1/conversations/${lobby}/join`);

    const teamCall = await callWith(teamChannel, owner, ...all);
    const lobbyCall = await callWith(lobby, owner, viaTeam);

    await owner.req("DELETE", `/v1/teams/${team}/members/${viaTeam.userId}`);
    await owner.req("DELETE", `/v1/branches/${branch}/members/${viaBranch.userId}`);
    await owner.req("PATCH", `/v1/orgs/${org}/members/${viaGuest.userId}`, { role: "GUEST" });
    await viaLeave.req("DELETE", `/v1/orgs/${org}/members/${viaLeave.userId}`);

    for (const c of [viaTeam, viaBranch, viaGuest, viaLeave]) expect(lk.inRoom(teamCall.room, c)).toBe(false);
    const left = await participantIds(owner, teamCall.id);
    expect(left.sort()).toEqual([owner.userId, stays.userId].sort());
    expect(lk.inRoom(teamCall.room, stays)).toBe(true);
    // viaTeam is still in the org-wide lobby, so its lobby call is untouched.
    expect(lk.inRoom(lobbyCall.room, viaTeam)).toBe(true);
    expect(await participantIds(owner, lobbyCall.id)).toContain(viaTeam.userId);
    expect(lk.removed.every((r) => r.room === teamCall.room)).toBe(true);
  });

  it("changes that remove nobody leave calls alone", async () => {
    const owner = await h.client("noopowner");
    const a = await h.client("noopa");
    const extra = await h.client("noopextra");
    const group = (await owner.req("POST", "/v1/conversations/group", { name: "Calm", memberIds: [a.userId] })).conversation.id;
    const call = await callWith(group, owner, a);
    await owner.req("PUT", `/v1/conversations/${group}/members/${extra.userId}`, {});
    await owner.req("DELETE", `/v1/conversations/${group}/members/${extra.userId}`);
    expect(lk.removed).toEqual([]);
    expect((await owner.req("GET", `/v1/calls/${call.id}`)).call.state).toBe("ACTIVE");
  });

  it("the last member's removal ends the call", async () => {
    const owner = await h.client("lastowner");
    const a = await h.client("lasta");
    const group = (await owner.req("POST", "/v1/conversations/group", { name: "Solo", memberIds: [a.userId] })).conversation.id;
    const call = await callWith(group, a); // only `a` is in the call
    await owner.req("DELETE", `/v1/conversations/${group}/members/${a.userId}`);
    const c = await owner.req("GET", `/v1/calls/${call.id}`);
    expect(c.call.state).toBe("ENDED");
    expect(c.call.endReason).toBe("empty");
  });

  it("a revoked device is evicted, without ending the call for others", async () => {
    const owner = await h.client("revowner");
    const a = await h.client("reva");
    const b = await h.client("revb");
    const group = (await owner.req("POST", "/v1/conversations/group", { name: "Rev", memberIds: [a.userId, b.userId] })).conversation.id;
    const call = await callWith(group, owner, a, b);
    await a.req("DELETE", `/v1/devices/${a.deviceId}`);
    expect(lk.inRoom(call.room, a)).toBe(false);
    expect(await participantIds(owner, call.id)).not.toContain(a.userId);
    expect((await owner.req("GET", `/v1/calls/${call.id}`)).call.state).toBe("ACTIVE");
  });
});

describe("eviction when LiveKit misbehaves", () => {
  it("LiveKit unreachable: call state is corrected at once and the cleanup job removes them later", async () => {
    const owner = await h.client("downowner");
    const a = await h.client("downa");
    const b = await h.client("downb");
    const group = (await owner.req("POST", "/v1/conversations/group", { name: "Down", memberIds: [a.userId, b.userId] })).conversation.id;
    const call = await callWith(group, owner, a, b);
    lk.unreachable = true;
    await owner.req("DELETE", `/v1/conversations/${group}/members/${a.userId}`);
    expect(await participantIds(owner, call.id)).not.toContain(a.userId);
    expect(lk.inRoom(call.room, a)).toBe(true); // still connected: LiveKit didn't answer

    lk.unreachable = false;
    const r = await runCleanup(h.ctx);
    expect(r.callEvictions).toBeGreaterThanOrEqual(1);
    expect(lk.inRoom(call.room, a)).toBe(false);
    expect(lk.inRoom(call.room, b)).toBe(true);
  });

  it("rejoining with a token issued before removal is refused by the webhook", async () => {
    const owner = await h.client("rejoinowner");
    const a = await h.client("rejoina");
    const group = (await owner.req("POST", "/v1/conversations/group", { name: "Rejoin", memberIds: [a.userId] })).conversation.id;
    const call = await callWith(group, owner, a);
    await owner.req("DELETE", `/v1/conversations/${group}/members/${a.userId}`);
    lk.join(call.room, a); // reconnects straight to LiveKit with the old token
    await fetch(`${h.baseUrl}/v1/livekit/webhook`, {
      method: "POST",
      headers: { "content-type": "application/webhook+json", authorization: "stub" },
      body: JSON.stringify({ event: "participant_joined", room: { name: call.room }, participant: { identity: `${a.userId}.${a.deviceId}` } }),
    });
    expect(lk.inRoom(call.room, a)).toBe(false);
    expect(await participantIds(owner, call.id)).not.toContain(a.userId);
  });

  it("an account disabled by the admin CLI (another process) is evicted by the cleanup job", async () => {
    const owner = await h.client("disowner");
    const a = await h.client("disa");
    const b = await h.client("disb");
    const group = (await owner.req("POST", "/v1/conversations/group", { name: "Dis", memberIds: [a.userId, b.userId] })).conversation.id;
    const call = await callWith(group, owner, a, b);
    h.ctx.db.prepare("UPDATE users SET disabled_at = ? WHERE id = ?").run(Date.now(), a.userId); // what `admin disable` writes
    await runCleanup(h.ctx);
    expect(lk.inRoom(call.room, a)).toBe(false);
    expect(lk.inRoom(call.room, b)).toBe(true);
  });
});
