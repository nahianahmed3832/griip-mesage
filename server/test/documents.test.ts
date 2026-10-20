import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { snippet } from "../src/documents/model.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/** Round 7 slice C4: workspace documents (docs/DOCUMENTS.md). Every way in must follow the document's access. */
let h: Harness;
let owner: GriipClient, alice: GriipClient, bob: GriipClient, guest: GriipClient, outsider: GriipClient;
let orgId: string, privateChannel: string;

const create = (c: GriipClient, body: object) => c.req("POST", `/v1/orgs/${orgId}/documents`, body).then((r) => r.document);
const search = async (c: GriipClient, q: string) => ((await c.req("GET", `/v1/search?q=${encodeURIComponent(q)}&types=documents`)).results.documents.items as { id: string; snippet: string }[]);

beforeAll(async () => {
  h = await startServer();
  [owner, alice, bob, guest, outsider] = await Promise.all(["docowner", "docalice", "docbob", "docguest", "docoutsider"].map((n) => h.client(n)));
  orgId = (await owner.req("POST", "/v1/orgs", { name: "Docs Co" })).org.id;
  for (const [c, role] of [[alice, "MEMBER"], [bob, "MEMBER"], [guest, "GUEST"]] as const) {
    const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role })).invite.code;
    await c.req("POST", "/v1/invites/accept", { code });
  }
  privateChannel = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "launch", scope: "ORG", kind: "PRIVATE", memberIds: [alice.userId] })).channel.id;
});
afterAll(async () => h.close());

describe("documents (round 7, C4)", () => {
  it("a workspace document: members comment by default, guests and outsiders see nothing", async () => {
    const d = await create(alice, { title: "Roadmap", content: "Ship the beta in November", access: "workspace", defaultRole: "comment" });
    expect(d).toMatchObject({ role: "admin", rev: 1, encryption: "server-readable" });
    const seen = (await bob.req("GET", `/v1/documents/${d.id}`)).document;
    expect(seen).toMatchObject({ role: "comment", content: "Ship the beta in November" });
    expect(seen.members).toBeUndefined(); // the sharing list is for its admins
    await expectStatus(bob.req("PUT", `/v1/documents/${d.id}`, { rev: 1, title: "x", content: "x" }), 403);
    await bob.req("POST", `/v1/documents/${d.id}/comments`, { body: "Which week?", quote: "November" });
    await expectStatus(guest.req("GET", `/v1/documents/${d.id}`), 404);
    await expectStatus(outsider.req("GET", `/v1/documents/${d.id}`), 404);
    await expectStatus(guest.req("POST", `/v1/orgs/${orgId}/documents`, { title: "g", access: "workspace" }), 403);
    expect((await guest.req("GET", `/v1/orgs/${orgId}/documents`)).documents).toEqual([]);
  });

  it("a restricted document: only the people listed, not even the workspace owner", async () => {
    const d = await create(alice, { title: "Salary bands draft", content: "confidential numbers", access: "restricted", members: [{ userId: bob.userId, role: "view" }] });
    expect((await bob.req("GET", `/v1/documents/${d.id}`)).document.role).toBe("view");
    await expectStatus(owner.req("GET", `/v1/documents/${d.id}`), 404);
    expect((await owner.req("GET", `/v1/orgs/${orgId}/documents`)).documents.map((x: { id: string }) => x.id)).not.toContain(d.id);
    expect(await search(owner, "confidential")).toEqual([]);
    expect((await search(bob, "confidential")).map((x) => x.id)).toEqual([d.id]);
    await expectStatus(owner.req("GET", `/v1/documents/${d.id}/export`), 404);
    await expectStatus(owner.req("GET", `/v1/documents/${d.id}/versions/1`), 404);
  });

  it("a channel document follows the private channel's membership", async () => {
    const d = await create(alice, { title: "Launch plan", content: "Press on Tuesday", access: "channel", channelId: privateChannel, defaultRole: "edit" });
    await expectStatus(bob.req("GET", `/v1/documents/${d.id}`), 404);
    expect(await search(bob, "press on tuesday")).toEqual([]);
    await owner.req("PUT", `/v1/conversations/${privateChannel}/members/${bob.userId}`, { role: "MEMBER" });
    expect((await bob.req("GET", `/v1/documents/${d.id}`)).document.role).toBe("edit");
    await owner.req("DELETE", `/v1/conversations/${privateChannel}/members/${bob.userId}`);
    await expectStatus(bob.req("GET", `/v1/documents/${d.id}`), 404);
    // Linking to a channel you're not in is refused.
    await expectStatus(bob.req("POST", `/v1/orgs/${orgId}/documents`, { title: "x", access: "channel", channelId: privateChannel }), 400);
  });

  it("saves keep versions; a stale save is refused; an old version comes back as a new one", async () => {
    const d = await create(alice, { title: "Notes", content: "v1", access: "workspace", defaultRole: "edit" });
    const v2 = (await bob.req("PUT", `/v1/documents/${d.id}`, { rev: 1, title: "Notes", content: "v2" })).document;
    expect(v2.rev).toBe(2);
    await expectStatus(alice.req("PUT", `/v1/documents/${d.id}`, { rev: 1, title: "Notes", content: "mine" }), 409);
    expect((await alice.req("GET", `/v1/documents/${d.id}/versions`)).versions.map((v: { rev: number }) => v.rev)).toEqual([2, 1]);
    expect((await alice.req("GET", `/v1/documents/${d.id}/versions/1`)).version.content).toBe("v1");
    const restored = (await alice.req("POST", `/v1/documents/${d.id}/versions/1/restore`)).document;
    expect(restored).toMatchObject({ rev: 3, content: "v1" });
  });

  it("archived documents are read-only until restored; only admins archive or delete", async () => {
    const d = await create(alice, { title: "Old policy", content: "x", access: "workspace", defaultRole: "edit" });
    await expectStatus(bob.req("POST", `/v1/documents/${d.id}/archive`), 403);
    await alice.req("POST", `/v1/documents/${d.id}/archive`);
    await expectStatus(bob.req("PUT", `/v1/documents/${d.id}`, { rev: 1, title: "x", content: "y" }), 409);
    await expectStatus(bob.req("POST", `/v1/documents/${d.id}/comments`, { body: "hi" }), 409);
    expect((await bob.req("GET", `/v1/orgs/${orgId}/documents?archived=true`)).documents.map((x: { id: string }) => x.id)).toContain(d.id);
    await alice.req("POST", `/v1/documents/${d.id}/restore`);
    await alice.req("DELETE", `/v1/documents/${d.id}`);
    await expectStatus(alice.req("GET", `/v1/documents/${d.id}`), 404);
  });

  it("comments: anyone who may comment resolves; only the author or an admin deletes", async () => {
    const d = await create(alice, { title: "FAQ", content: "Q?", access: "workspace", defaultRole: "comment" });
    const c = (await bob.req("POST", `/v1/documents/${d.id}/comments`, { body: "Add pricing" })).comment;
    await owner.req("POST", `/v1/document-comments/${c.id}/resolve`, { resolved: true });
    expect((await alice.req("GET", `/v1/documents/${d.id}/comments`)).comments[0].resolved).toBe(true);
    await expectStatus(owner.req("DELETE", `/v1/document-comments/${c.id}`), 403);
    await alice.req("DELETE", `/v1/document-comments/${c.id}`);
    expect((await alice.req("GET", `/v1/documents/${d.id}/comments`)).comments).toEqual([]);
  });

  it("sharing: admins change access, the list must be workspace members, and leaving the workspace ends access", async () => {
    const d = await create(alice, { title: "Team page", content: "hello", access: "restricted" });
    await expectStatus(alice.req("PUT", `/v1/documents/${d.id}/sharing`, { access: "restricted", members: [{ userId: outsider.userId, role: "view" }] }), 400);
    const shared = (await alice.req("PUT", `/v1/documents/${d.id}/sharing`, { access: "restricted", members: [{ userId: bob.userId, role: "edit" }] })).document;
    expect(shared.members.map((m: { userId: string }) => m.userId)).toContain(bob.userId);
    await expectStatus(bob.req("PUT", `/v1/documents/${d.id}/sharing`, { access: "workspace" }), 403);
    await owner.req("DELETE", `/v1/orgs/${orgId}/members/${bob.userId}`);
    await expectStatus(bob.req("GET", `/v1/documents/${d.id}`), 404);
  });

  it("export is the Markdown the reader may see; search shows an excerpt", async () => {
    const d = await create(alice, { title: "Handbook", content: "Our holidays are listed below. Remote work is fine on Fridays.", access: "workspace" });
    const md: string = await alice.req("GET", `/v1/documents/${d.id}/export`);
    expect(String(md).startsWith("# Handbook\n\n")).toBe(true);
    const hits = await search(alice, "remote work");
    expect(hits[0].id).toBe(d.id);
    expect(hits[0].snippet).toContain("Remote work");
    expect(snippet("# Title\n\nSome **bold** text", "bold")).toBe("Title Some bold text");
    expect((await alice.req("GET", "/v1/server-info")).features).toContain("documents");
    expect((await alice.req("GET", "/v1/documents/templates")).templates.map((t: { id: string }) => t.id)).toContain("meeting");
  });
});
