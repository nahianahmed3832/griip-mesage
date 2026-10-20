import crypto from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { AiProvider, AiRequest, AiResult } from "../src/ai/provider.js";
import { parseWebhook, verifySignature } from "../src/inbox/meta.js";
import { replyRule, textFits } from "../src/inbox/model.js";
import type { GriipClient } from "./support/griipClient.js";
import { expectStatus, type Harness, startServer } from "./support/harness.js";

/**
 * Round 8: the Unified Inbox (docs/UNIFIED_INBOX.md), against a fake Meta that implements the documented endpoints.
 * Synthetic customers only; nothing reaches a real provider.
 */
const SECRET = "app-secret-for-tests";
const VERIFY = "verify-token-for-tests";
const PAGE = "1000001";
const PHONE = "2000002";
const WABA = "3000003";
const PAGE_TOKEN = "EAAPAGE-token-for-tests-0001";
const WA_TOKEN = "EAAWA-system-user-token-0002";

/** Meta, as documented: Page and phone number lookups, subscriptions, the send endpoints, templates. */
class FakeMeta {
  sent: { url: string; body: Record<string, unknown> }[] = [];
  failures: number[] = []; // HTTP statuses for the next sends
  private n = 0;
  fetch = async (url: string, init?: RequestInit): Promise<Response> => {
    const auth = String((init?.headers as Record<string, string> | undefined)?.authorization ?? "");
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
    const u = new URL(url);
    const token = auth.replace("Bearer ", "");
    if (token !== PAGE_TOKEN && token !== WA_TOKEN) return json({ error: { code: 190, message: "Invalid OAuth access token" } }, 400);
    if (init?.method === "POST" && u.pathname.endsWith("/messages")) {
      const failure = this.failures.shift();
      if (failure) return json({ error: { code: failure >= 500 ? 2 : 190, message: "echo of the customer's text" } }, failure);
      const body = JSON.parse(String(init.body)) as Record<string, unknown>;
      this.sent.push({ url, body });
      this.n++;
      return u.pathname.includes(PHONE) ? json({ messages: [{ id: `wamid.out${this.n}` }] }) : json({ recipient_id: "x", message_id: `m_out${this.n}` });
    }
    if (u.pathname.endsWith("/subscribed_apps")) return json({ success: true });
    if (u.pathname.endsWith(`/${PAGE}`)) return json({ id: PAGE, name: "Acme Shop" });
    if (u.pathname.endsWith(`/${PHONE}`)) return json({ id: PHONE, display_phone_number: "+1 555 0100", verified_name: "Acme Shop WA" });
    if (u.pathname.endsWith(`/${WABA}/message_templates`)) return json({ data: [{ name: "order_update", language: "en_US", status: "APPROVED", category: "UTILITY" }] });
    return json({ error: { code: 100, message: "Unknown path" } }, 400);
  };
}

class FakeAi implements AiProvider {
  readonly id = "anthropic" as const;
  readonly model = "fake";
  sent: AiRequest[] = [];
  replies: string[] = [];
  async complete(req: AiRequest): Promise<AiResult> {
    this.sent.push(req);
    return { text: this.replies.shift() ?? "Here is a draft reply.", inputTokens: 10, outputTokens: 5 };
  }
}

let h: Harness;
let owner: GriipClient, agent: GriipClient, member: GriipClient, outsider: GriipClient, other: GriipClient;
let orgId: string, otherOrg: string;
const fake = new FakeMeta();
const ai = new FakeAi();
let mid = 0;

const sign = (raw: string) => `sha256=${crypto.createHmac("sha256", SECRET).update(raw).digest("hex")}`;
async function webhook(payload: unknown, signature?: string) {
  const raw = JSON.stringify(payload);
  return fetch(`${h.baseUrl}/v1/inbox/webhooks/meta`, { method: "POST", headers: { "content-type": "application/json", "x-hub-signature-256": signature ?? sign(raw) }, body: raw });
}
const messengerText = (customer: string, text: string, at = Date.now(), page = PAGE) => ({
  object: "page",
  entry: [{ id: page, time: at, messaging: [{ sender: { id: customer }, recipient: { id: page }, timestamp: at, message: { mid: `m_in${++mid}`, text } }] }],
});
const whatsappText = (from: string, text: string, at = Date.now()) => ({
  object: "whatsapp_business_account",
  entry: [{ id: WABA, changes: [{ field: "messages", value: { messaging_product: "whatsapp", metadata: { phone_number_id: PHONE }, contacts: [{ wa_id: from, profile: { name: "Rina" } }], messages: [{ from, id: `wamid.in${++mid}`, timestamp: String(Math.floor(at / 1000)), type: "text", text: { body: text } }] } }] }],
});
const list = (c: GriipClient, view = "all", o = orgId) => c.req("GET", `/v1/orgs/${o}/inbox/conversations?view=${view}`);
const tick = () => h.ctx.inbox.worker.tick();

beforeAll(async () => {
  h = await startServer(
    { INBOX_TICK_MS: "0", WORKFLOW_TICK_MS: "0", INTEGRATIONS_TOKEN_KEY: crypto.randomBytes(32).toString("base64"), META_APP_SECRET: SECRET, META_WEBHOOK_VERIFY_TOKEN: VERIFY },
    { integrationsFetch: fake.fetch },
  );
  [owner, agent, member, outsider, other] = await Promise.all(["ibowner", "ibagent", "ibmember", "iboutsider", "ibother"].map((n) => h.client(n)));
  orgId = (await owner.req("POST", "/v1/orgs", { name: "Acme" })).org.id;
  otherOrg = (await other.req("POST", "/v1/orgs", { name: "Other Co" })).org.id;
  for (const c of [agent, member]) {
    const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER" })).invite.code;
    await c.req("POST", "/v1/invites/accept", { code });
  }
  const role = (await owner.req("POST", `/v1/orgs/${orgId}/roles`, { template: "support_agent" })).role;
  await owner.req("PUT", `/v1/roles/${role.id}/members/${agent.userId}`);
});
afterAll(async () => h.close());
beforeEach(() => {
  fake.sent = [];
  fake.failures = [];
});

describe("inbox setup (round 8)", () => {
  it("lists each provider honestly: Meta ready with config, TikTok awaiting access", async () => {
    const providers = (await owner.req("GET", `/v1/orgs/${orgId}/inbox/providers`)).providers as { id: string; state: string }[];
    expect(Object.fromEntries(providers.map((p) => [p.id, p.state]))).toEqual({ messenger: "ready", instagram: "ready", whatsapp: "ready", tiktok: "awaiting_access" });
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/inbox/accounts`, { provider: "tiktok", externalId: "1", token: "x".repeat(30) }), 409);
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/inbox/providers`), 403);
  });

  it("answers Meta's subscription check only with the verify token", async () => {
    const ok = await fetch(`${h.baseUrl}/v1/inbox/webhooks/meta?hub.mode=subscribe&hub.verify_token=${VERIFY}&hub.challenge=12345`);
    expect([ok.status, await ok.text()]).toEqual([200, "12345"]);
    expect((await fetch(`${h.baseUrl}/v1/inbox/webhooks/meta?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=1`)).status).toBe(403);
  });

  it("connects accounts with a checked token, sealed at rest, one workspace per account", async () => {
    await expectStatus(agent.req("POST", `/v1/orgs/${orgId}/inbox/accounts`, { provider: "messenger", externalId: PAGE, token: PAGE_TOKEN }), 403);
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/inbox/accounts`, { provider: "messenger", externalId: PAGE, token: "EAA-not-a-real-token-0000" }), 400);
    const page = (await owner.req("POST", `/v1/orgs/${orgId}/inbox/accounts`, { provider: "messenger", externalId: PAGE, token: PAGE_TOKEN })).account;
    expect(page).toMatchObject({ provider: "messenger", name: "Acme Shop", status: "active" });
    const wa = (await owner.req("POST", `/v1/orgs/${orgId}/inbox/accounts`, { provider: "whatsapp", externalId: PHONE, businessAccountId: WABA, token: WA_TOKEN })).account;
    expect(wa).toMatchObject({ name: "Acme Shop WA", number: "+1 555 0100" });
    const row = h.ctx.db.prepare("SELECT token_sealed FROM inbox_accounts WHERE id = ?").get(page.id) as { token_sealed: string };
    expect(row.token_sealed).not.toContain(PAGE_TOKEN);
    expect(JSON.stringify(await owner.req("GET", `/v1/orgs/${orgId}/inbox/accounts`))).not.toContain(PAGE_TOKEN);
    const taken = await expectStatus(other.req("POST", `/v1/orgs/${otherOrg}/inbox/accounts`, { provider: "messenger", externalId: PAGE, token: PAGE_TOKEN }), 409);
    expect(taken.error.message).toContain("another workspace");
    const audits = h.ctx.db.prepare("SELECT action FROM audit_log WHERE org_id = ? AND action LIKE 'inbox.account%'").all(orgId);
    expect(audits.length).toBe(2);
  });
});

describe("inbox messages (round 8)", () => {
  it("refuses unsigned deliveries, stores signed ones once, and shows them only to the workspace's agents", async () => {
    const payload = messengerText("psid-1", "Do you ship to Dhaka?");
    expect((await webhook(payload, "sha256=deadbeef")).status).toBe(401);
    expect((await webhook(payload)).status).toBe(200);
    expect((await webhook(payload)).status).toBe(200); // Meta's redelivery
    await tick();
    const r = await list(agent);
    expect(r.conversations).toHaveLength(1);
    const c = r.conversations[0];
    expect(c).toMatchObject({ provider: "messenger", status: "waiting_agent", unread: 1, preview: "Do you ship to Dhaka?", customer: { name: null } });
    const d = await agent.req("GET", `/v1/inbox/conversations/${c.id}`);
    expect(d.messages.map((m: { text: string }) => m.text)).toEqual(["Do you ship to Dhaka?"]);
    expect(d.reply.rule).toEqual({ allowed: true, mode: "free" });
    await expectStatus(list(member), 403);
    await expectStatus(member.req("GET", `/v1/inbox/conversations/${c.id}`), 404);
    await expectStatus(outsider.req("GET", `/v1/inbox/conversations/${c.id}`), 404);
    expect((await list(other, "all", otherOrg).catch(() => ({ conversations: [] }))).conversations).toEqual([]);
    const stored = h.ctx.db.prepare("SELECT body_sealed FROM inbox_messages WHERE conversation_id = ?").get(c.id) as { body_sealed: string };
    expect(stored.body_sealed).not.toContain("Dhaka");
    const ev = h.ctx.db.prepare("SELECT payload_sealed FROM inbox_events").all() as { payload_sealed: string }[];
    expect(ev.every((e) => !e.payload_sealed.includes("Dhaka"))).toBe(true);
  });

  it("ignores deliveries for accounts not connected here", async () => {
    await webhook(messengerText("psid-9", "hello", Date.now(), "9999999"));
    await tick();
    expect(h.ctx.db.prepare("SELECT COUNT(*) n FROM inbox_contacts WHERE external_user_id = 'psid-9'").get()).toEqual({ n: 0 });
  });

  it("replies within the window, follows delivery reports forward only, keeps notes internal", async () => {
    const c = (await list(agent)).conversations[0];
    await agent.req("POST", `/v1/inbox/conversations/${c.id}/notes`, { text: "VIP customer, be quick" });
    expect(fake.sent).toEqual([]);
    const m = (await agent.req("POST", `/v1/inbox/conversations/${c.id}/replies`, { kind: "text", text: "Yes, we ship to Dhaka!" })).message;
    expect(m).toMatchObject({ status: "sent", direction: "out" });
    expect(fake.sent[0].body).toMatchObject({ recipient: { id: "psid-1" }, messaging_type: "RESPONSE", message: { text: "Yes, we ship to Dhaka!" } });
    const ext = (h.ctx.db.prepare("SELECT external_id FROM inbox_messages WHERE id = ?").get(m.id) as { external_id: string }).external_id;
    const at = Date.now();
    await webhook({ object: "page", entry: [{ id: PAGE, messaging: [{ sender: { id: "psid-1" }, recipient: { id: PAGE }, timestamp: at, read: { watermark: at + 1000 } }] }] });
    await webhook({ object: "page", entry: [{ id: PAGE, messaging: [{ sender: { id: "psid-1" }, recipient: { id: PAGE }, timestamp: at, delivery: { mids: [ext] } }] }] });
    await tick();
    const d = await agent.req("GET", `/v1/inbox/conversations/${c.id}`);
    expect(d.messages.find((x: { id: string }) => x.id === m.id).status).toBe("read"); // a later "delivered" doesn't move it back
    expect(d.messages.find((x: { direction: string }) => x.direction === "note").text).toBe("VIP customer, be quick");
    expect(d.conversation).toMatchObject({ status: "waiting_customer", assigneeId: agent.userId });
    await expectStatus(member.req("POST", `/v1/inbox/conversations/${c.id}/replies`, { kind: "text", text: "hi" }), 404);
  });

  it("enforces the reply windows: Messenger closes, WhatsApp needs a template", async () => {
    const old = Date.now() - 26 * 3_600_000;
    await webhook(messengerText("psid-old", "old question", old));
    await webhook(whatsappText("8801700000000", "Is my order ready?", old));
    await tick();
    const convs = (await list(agent)).conversations as { id: string; provider: string; customer: { name: string | null; handle: string | null } }[];
    const waConv = convs.find((x) => x.provider === "whatsapp");
    expect(waConv?.customer).toEqual(expect.objectContaining({ name: "Rina", handle: "+8801700000000" }));
    const oldFb = (h.ctx.db.prepare("SELECT c.id FROM inbox_conversations c JOIN inbox_contacts k ON k.id = c.contact_id WHERE k.external_user_id = 'psid-old'").get() as { id: string }).id;
    expect((await expectStatus(agent.req("POST", `/v1/inbox/conversations/${oldFb}/replies`, { kind: "text", text: "Sorry for the wait" }), 409)).error.code).toBe("window_closed");
    expect((await expectStatus(agent.req("POST", `/v1/inbox/conversations/${waConv?.id}/replies`, { kind: "text", text: "Ready!" }), 409)).error.code).toBe("template_required");
    const accounts = (await owner.req("GET", `/v1/orgs/${orgId}/inbox/accounts`)).accounts as { id: string; provider: string }[];
    const waId = accounts.find((a) => a.provider === "whatsapp")?.id;
    expect((await agent.req("GET", `/v1/orgs/${orgId}/inbox/accounts/${waId}/templates`)).templates).toEqual([{ name: "order_update", language: "en_US", category: "UTILITY" }]);
    const t = (await agent.req("POST", `/v1/inbox/conversations/${waConv?.id}/replies`, { kind: "template", name: "order_update", language: "en_US" })).message;
    expect(t).toMatchObject({ status: "sent", kind: "template", text: "Template: order_update (en_US)" });
    expect(fake.sent[0].body).toMatchObject({ messaging_product: "whatsapp", to: "8801700000000", type: "template", template: { name: "order_update", language: { code: "en_US" } } });
  });

  it("retries a passing failure, and a refused token asks admins to reconnect", async () => {
    const c = (await list(agent)).conversations.find((x: { provider: string; preview: string }) => x.provider === "messenger" && x.preview.startsWith("Yes"));
    await webhook(messengerText("psid-1", "One more question"));
    await tick();
    fake.failures = [503];
    const m = (await agent.req("POST", `/v1/inbox/conversations/${c.id}/replies`, { kind: "text", text: "Sure" })).message;
    expect(m.status).toBe("queued");
    h.ctx.db.prepare("UPDATE inbox_messages SET next_attempt_at = 0 WHERE id = ?").run(m.id);
    await tick();
    expect((h.ctx.db.prepare("SELECT status FROM inbox_messages WHERE id = ?").get(m.id) as { status: string }).status).toBe("sent");
    fake.failures = [401];
    const bad = (await agent.req("POST", `/v1/inbox/conversations/${c.id}/replies`, { kind: "text", text: "Again" })).message;
    expect(bad).toMatchObject({ status: "failed", errorKind: "unauthorized" });
    const acc = ((await owner.req("GET", `/v1/orgs/${orgId}/inbox/accounts`)).accounts as { provider: string; status: string }[]).find((a) => a.provider === "messenger");
    expect(acc?.status).toBe("needs_reauth");
    expect((await expectStatus(agent.req("POST", `/v1/inbox/conversations/${c.id}/replies`, { kind: "text", text: "x" }), 409)).error.code).toBe("inbox_reconnect");
    h.ctx.db.prepare("UPDATE inbox_accounts SET status = 'active', last_error = NULL WHERE provider = 'messenger'").run();
  });

  it("assignment uses revisions, is audited, and only reaches people with inbox access", async () => {
    const c = (await list(agent)).conversations[0];
    await expectStatus(agent.req("PATCH", `/v1/inbox/conversations/${c.id}`, { rev: c.rev, assigneeId: member.userId }), 400);
    const u = (await agent.req("PATCH", `/v1/inbox/conversations/${c.id}`, { rev: c.rev, assigneeId: owner.userId, priority: "high", tags: ["shipping"] })).conversation;
    expect(u).toMatchObject({ assigneeId: owner.userId, priority: "high", tags: ["shipping"] });
    await expectStatus(agent.req("PATCH", `/v1/inbox/conversations/${c.id}`, { rev: c.rev, status: "resolved" }), 409);
    const r = (await owner.req("PATCH", `/v1/inbox/conversations/${c.id}`, { rev: u.rev, status: "resolved" })).conversation;
    expect(r.status).toBe("resolved");
    expect((await list(agent, "resolved")).conversations.map((x: { id: string }) => x.id)).toContain(c.id);
    expect(h.ctx.db.prepare("SELECT COUNT(*) n FROM audit_log WHERE action = 'inbox.conversation_updated' AND target = ?").get(c.id)).toEqual({ n: 2 });
  });

  it("queues route new conversations; search finds names and tags, not message bodies", async () => {
    const q = (await owner.req("POST", `/v1/orgs/${orgId}/inbox/queues`, { name: "Sales", memberIds: [agent.userId] })).queue;
    await expectStatus(owner.req("POST", `/v1/orgs/${orgId}/inbox/queues`, { name: "Bad", memberIds: [member.userId] }), 400);
    const s = (await owner.req("GET", `/v1/orgs/${orgId}/inbox/settings`)).settings;
    await owner.req("PUT", `/v1/orgs/${orgId}/inbox/settings`, { ...s, defaultQueueId: q.id });
    await webhook(whatsappText("8801800000000", "Price of the blue bag?"));
    await tick();
    const found = (await agent.req("GET", `/v1/orgs/${orgId}/inbox/conversations?q=rina`)).conversations;
    expect(found.length).toBeGreaterThan(0);
    const byQueue = (await agent.req("GET", `/v1/orgs/${orgId}/inbox/conversations?queueId=${q.id}`)).conversations;
    expect(byQueue).toHaveLength(1);
    expect((await agent.req("GET", `/v1/orgs/${orgId}/inbox/conversations?q=blue%20bag`)).conversations).toEqual([]);
  });
});

describe("inbox AI (round 8)", () => {
  let convId: string;
  beforeAll(async () => {
    (h.ctx.config.ai as { providers: readonly AiProvider[] }).providers = [ai];
    await owner.req("PUT", `/v1/orgs/${orgId}/ai`, { enabled: true });
    await webhook(messengerText("psid-ai", "What are your opening hours?"));
    await tick();
    convId = (h.ctx.db.prepare("SELECT c.id FROM inbox_conversations c JOIN inbox_contacts k ON k.id = c.contact_id WHERE k.external_user_id = 'psid-ai'").get() as { id: string }).id;
    await agent.req("POST", `/v1/inbox/conversations/${convId}/notes`, { text: "secret internal note" });
  });
  beforeEach(() => {
    ai.sent = [];
    ai.replies = [];
  });

  it("is off until an inbox admin chooses a mode; drafts need consent and never include notes", async () => {
    expect((await expectStatus(agent.req("POST", `/v1/inbox/conversations/${convId}/ai/draft`, { consent: true }), 409)).error.code).toBe("inbox_ai_off");
    const s = (await owner.req("GET", `/v1/orgs/${orgId}/inbox/settings`)).settings;
    await expectStatus(agent.req("PUT", `/v1/orgs/${orgId}/inbox/settings`, { ...s, aiMode: "draft" }), 403);
    await owner.req("PUT", `/v1/orgs/${orgId}/inbox/settings`, { ...s, aiMode: "draft" });
    await expectStatus(agent.req("POST", `/v1/inbox/conversations/${convId}/ai/draft`, { consent: false }), 400);
    const d = (await agent.req("POST", `/v1/inbox/conversations/${convId}/ai/draft`, { consent: true })).result;
    expect(d).toMatchObject({ text: "Here is a draft reply.", label: "ai_draft" });
    expect(ai.sent[0].user).toContain("What are your opening hours?");
    expect(ai.sent[0].user).not.toContain("secret internal note");
    expect(fake.sent).toEqual([]); // a draft is never sent by itself
    const sent = (await agent.req("POST", `/v1/inbox/conversations/${convId}/replies`, { kind: "text", text: d.text, fromAiDraft: true })).message;
    expect(sent.origin).toBe("ai_draft");
  });

  it("automatic replies: only allowed intents, from approved documents, escalating everything else", async () => {
    const doc = (await owner.req("POST", `/v1/orgs/${orgId}/documents`, { title: "Shop FAQ", content: "We are open 9am to 6pm, Saturday to Thursday.", access: "workspace" })).document;
    const restricted = (await owner.req("POST", `/v1/orgs/${orgId}/documents`, { title: "Margins", content: "secret", access: "restricted" })).document;
    const s = (await owner.req("GET", `/v1/orgs/${orgId}/inbox/settings`)).settings;
    await expectStatus(owner.req("PUT", `/v1/orgs/${orgId}/inbox/settings`, { ...s, knowledgeDocumentIds: [restricted.id] }), 400);
    await owner.req("PUT", `/v1/orgs/${orgId}/inbox/settings`, { ...s, aiMode: "auto", autoIntents: ["opening_hours"], knowledgeDocumentIds: [doc.id], autoMinConfidence: 80, defaultQueueId: null });

    // A new customer asks an allowed question: classified, answered from the FAQ, sent automatically.
    ai.replies = [JSON.stringify({ intent: "opening_hours", confidence: 95, escalate: false }), "We're open 9am to 6pm, Saturday to Thursday."];
    await webhook(messengerText("psid-auto", "When are you open?"));
    await tick();
    const auto = (h.ctx.db.prepare("SELECT c.* FROM inbox_conversations c JOIN inbox_contacts k ON k.id = c.contact_id WHERE k.external_user_id = 'psid-auto'").get() as { id: string; handling: string });
    expect(auto.handling).toBe("ai_auto");
    await new Promise((r) => setTimeout(r, 50));
    const msgs = (await agent.req("GET", `/v1/inbox/conversations/${auto.id}`)).messages as { direction: string; origin: string; text: string; status: string }[];
    expect(msgs.find((m) => m.direction === "out")).toMatchObject({ origin: "ai_auto", text: "We're open 9am to 6pm, Saturday to Thursday.", status: "sent" });
    expect(ai.sent[1].user).toContain("Saturday to Thursday");

    // A refund request is never automatic: escalated with a reason and the holding reply.
    ai.replies = [JSON.stringify({ intent: "refund", confidence: 99, escalate: true, reason: "Refund request" })];
    fake.sent = [];
    await webhook(messengerText("psid-auto", "I want my money back"));
    await tick();
    await new Promise((r) => setTimeout(r, 50));
    const esc = (await agent.req("GET", `/v1/inbox/conversations/${auto.id}`)).conversation;
    expect(esc).toMatchObject({ handling: "escalated" });
    expect(esc.escalationReason).toContain("refund");
    expect(fake.sent).toEqual([]); // an automatic reply already went out this hour, so no holding reply on top

    // A person takes over: AI stops in this conversation.
    const taken = (await agent.req("POST", `/v1/inbox/conversations/${auto.id}/claim`)).conversation;
    expect(taken.handling).toBe("human");
    ai.sent = [];
    await webhook(messengerText("psid-auto", "Hello?"));
    await tick();
    expect(ai.sent).toEqual([]);
  });

  it("the pause switch stops automatic replies everywhere", async () => {
    const s = (await owner.req("GET", `/v1/orgs/${orgId}/inbox/settings`)).settings;
    await owner.req("PUT", `/v1/orgs/${orgId}/inbox/settings`, { ...s, autoPaused: true });
    await webhook(messengerText("psid-paused", "When are you open?"));
    await tick();
    expect(ai.sent).toEqual([]);
    const c = h.ctx.db.prepare("SELECT c.handling FROM inbox_conversations c JOIN inbox_contacts k ON k.id = c.contact_id WHERE k.external_user_id = 'psid-paused'").get() as { handling: string };
    expect(c.handling).toBe("human");
  });
});

describe("inbox privacy and operations (round 8)", () => {
  it("exports and erases a customer's data (admins only, audited)", async () => {
    const c = (await list(agent)).conversations[0];
    await expectStatus(agent.req("GET", `/v1/inbox/conversations/${c.id}/export`), 403);
    const ex = await owner.req("GET", `/v1/inbox/conversations/${c.id}/export`);
    expect(ex.messages.length).toBeGreaterThan(0);
    await expectStatus(agent.req("DELETE", `/v1/inbox/conversations/${c.id}`), 403);
    await owner.req("DELETE", `/v1/inbox/conversations/${c.id}`);
    await expectStatus(owner.req("GET", `/v1/inbox/conversations/${c.id}`), 404);
    expect(h.ctx.db.prepare("SELECT COUNT(*) n FROM inbox_messages WHERE conversation_id = ?").get(c.id)).toEqual({ n: 0 });
  });

  it("reports counts from recorded events, and platform health shows no content", async () => {
    const r = (await owner.req("GET", `/v1/orgs/${orgId}/inbox/report?days=7`)).report;
    expect(r.messages.received).toBeGreaterThan(0);
    expect(r.messages.sentAutomatically).toBeGreaterThan(0);
    expect(r.byProvider.find((p: { provider: string }) => p.provider === "tiktok").received).toBe(0);
    await expectStatus(agent.req("GET", `/v1/orgs/${orgId}/inbox/report`), 403);
    await expectStatus(owner.req("GET", "/v1/admin/inbox/health"), 404);
    (h.ctx.config.integrations.platformAdmins as Set<string>).add(owner.userId);
    const health = JSON.stringify((await owner.req("GET", "/v1/admin/inbox/health")).health);
    expect(health).not.toContain("Dhaka");
    expect(health).toContain("messenger");
  });

  it("disconnecting erases the token and stops routing new messages", async () => {
    const acc = ((await owner.req("GET", `/v1/orgs/${orgId}/inbox/accounts`)).accounts as { id: string; provider: string }[]).find((a) => a.provider === "messenger");
    await owner.req("DELETE", `/v1/orgs/${orgId}/inbox/accounts/${acc?.id}`);
    expect(h.ctx.db.prepare("SELECT token_sealed, live_key FROM inbox_accounts WHERE id = ?").get(acc?.id)).toEqual({ token_sealed: null, live_key: null });
    await webhook(messengerText("psid-after", "anyone?"));
    await tick();
    expect(h.ctx.db.prepare("SELECT COUNT(*) n FROM inbox_contacts WHERE external_user_id = 'psid-after'").get()).toEqual({ n: 0 });
    // Now another workspace may connect that Page.
    const again = (await other.req("POST", `/v1/orgs/${otherOrg}/inbox/accounts`, { provider: "messenger", externalId: PAGE, token: PAGE_TOKEN })).account;
    expect(again.status).toBe("active");
  });
});

describe("inbox rules (round 8)", () => {
  it("signature check is HMAC-SHA256 over the raw body", () => {
    const raw = Buffer.from('{"object":"page"}');
    expect(verifySignature(raw, sign(raw.toString()), SECRET)).toBe(true);
    expect(verifySignature(raw, sign("{}"), SECRET)).toBe(false);
    expect(verifySignature(raw, undefined, SECRET)).toBe(false);
    expect(verifySignature(raw, sign(raw.toString()), null)).toBe(false);
  });

  it("windows: 24 h free; Messenger 7 days for people with the Human Agent tag, never for AI; WhatsApp templates", () => {
    const t = Date.now();
    const h25 = t - 25 * 3_600_000;
    expect(replyRule("messenger", t - 3_600_000, t, { humanAgentTag: false, byAi: true })).toEqual({ allowed: true, mode: "free" });
    expect(replyRule("messenger", h25, t, { humanAgentTag: true, byAi: false })).toEqual({ allowed: true, mode: "human_agent_tag" });
    expect(replyRule("messenger", h25, t, { humanAgentTag: true, byAi: true })).toEqual({ allowed: false, reason: "window_closed" });
    expect(replyRule("whatsapp", h25, t, { humanAgentTag: true, byAi: false })).toEqual({ allowed: false, reason: "template_required" });
    expect(replyRule("tiktok", t, t, { humanAgentTag: false, byAi: false })).toEqual({ allowed: false, reason: "unsupported" });
    expect(textFits("instagram", "é".repeat(500))).toBe(true);
    expect(textFits("instagram", "é".repeat(501))).toBe(false);
  });

  it("parses Instagram and WhatsApp payloads and skips echoes and malformed input", () => {
    const ig = parseWebhook({ object: "instagram", entry: [{ id: "17841", messaging: [
      { sender: { id: "igsid1" }, recipient: { id: "17841" }, timestamp: 1, message: { mid: "aW1", text: "hi" } },
      { sender: { id: "17841" }, recipient: { id: "igsid1" }, timestamp: 2, message: { mid: "aW2", text: "echo", is_echo: true } },
      { sender: { id: "igsid1" }, recipient: { id: "17841" }, timestamp: 3, message: { mid: "aW3", attachments: [{ type: "image", payload: { url: "https://lookaside.fbsbx.com/x" } }] } },
    ] }] });
    expect(ig.inbound.map((m) => [m.externalId, m.kind])).toEqual([["aW1", "text"], ["aW3", "attachment"]]);
    const wa = parseWebhook({ object: "whatsapp_business_account", entry: [{ changes: [{ value: { metadata: { phone_number_id: "P" }, statuses: [{ id: "wamid.1", status: "delivered", timestamp: "10" }], messages: [{ from: "1", id: "wamid.2", timestamp: "11", type: "image", image: { id: "media1", mime_type: "image/jpeg", caption: "look" } }] } }] }] });
    expect(wa.statuses).toEqual([{ provider: "whatsapp", accountExternalId: "P", externalId: "wamid.1", state: "delivered", at: 10_000 }]);
    expect(wa.inbound[0]).toMatchObject({ kind: "attachment", text: "look", attachments: [{ type: "image", ref: "media1", mime: "image/jpeg" }] });
    expect(parseWebhook({ object: "page", entry: "nope" })).toEqual({ inbound: [], statuses: [] });
    expect(parseWebhook(null)).toEqual({ inbound: [], statuses: [] });
  });
});

describe("inbox agents (round 8)", () => {
  it("lists people with inbox access, and an empty assignee clears the assignment", async () => {
    const agents = (await agent.req("GET", `/v1/orgs/${orgId}/inbox/agents`)).agents as { id: string; canReply: boolean }[];
    expect(agents.map((a) => a.id).sort()).toEqual([owner.userId, agent.userId].sort());
    expect(agents.every((a) => a.canReply)).toBe(true);
    await expectStatus(member.req("GET", `/v1/orgs/${orgId}/inbox/agents`), 403);
    await webhook(whatsappText("8801900000000", "hello"));
    await tick();
    const c = (await agent.req("GET", `/v1/orgs/${orgId}/inbox/conversations?q=8801900000000`)).conversations[0];
    const claimed = (await agent.req("POST", `/v1/inbox/conversations/${c.id}/claim`)).conversation;
    expect(claimed.assigneeId).toBe(agent.userId);
    const cleared = (await agent.req("PATCH", `/v1/inbox/conversations/${c.id}`, { rev: claimed.rev, assigneeId: "" })).conversation;
    expect(cleared.assigneeId).toBeNull();
  });
});

describe("inbox phone-test fixes (round 8)", () => {
  it("keeps same-second WhatsApp messages in the order they arrived", async () => {
    const at = Date.now();
    await webhook(whatsappText("8801955500000", "first question", at));
    await webhook(whatsappText("8801955500000", "second, same second", at));
    await tick();
    const c = (await agent.req("GET", `/v1/orgs/${orgId}/inbox/conversations?q=8801955500000`)).conversations[0];
    const texts = (await agent.req("GET", `/v1/inbox/conversations/${c.id}`)).messages.map((m: { text: string }) => m.text);
    expect(texts).toEqual(["first question", "second, same second"]);
  });

  it("a delivery report tells the apps to refresh that conversation", async () => {
    const c = (await agent.req("GET", `/v1/orgs/${orgId}/inbox/conversations?q=8801955500000`)).conversations[0];
    const m = (await agent.req("POST", `/v1/inbox/conversations/${c.id}/replies`, { kind: "text", text: "Thanks!" })).message;
    const ext = (h.ctx.db.prepare("SELECT external_id FROM inbox_messages WHERE id = ?").get(m.id) as { external_id: string }).external_id;
    const sent: unknown[] = [];
    const original = h.ctx.hub.sendToUsers.bind(h.ctx.hub);
    h.ctx.hub.sendToUsers = (ids, msg, except) => {
      sent.push(msg);
      original(ids, msg, except);
    };
    try {
      await webhook({ object: "whatsapp_business_account", entry: [{ changes: [{ value: { metadata: { phone_number_id: PHONE }, statuses: [{ id: ext, status: "delivered", timestamp: String(Math.floor(Date.now() / 1000)) }] } }] }] });
      await tick();
    } finally {
      h.ctx.hub.sendToUsers = original;
    }
    expect(sent).toContainEqual({ t: "inbox.updated", orgId, conversationId: c.id });
    expect((h.ctx.db.prepare("SELECT status FROM inbox_messages WHERE id = ?").get(m.id) as { status: string }).status).toBe("delivered");
  });
});
