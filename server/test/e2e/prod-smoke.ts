/**
 * Production smoke test: real TLS, real nginx WebSocket proxying, real relay.
 *   GRIIP_INVITE=<registration invite, 2 uses> npx tsx test/e2e/prod-smoke.ts https://msg.griipbuddy.cloud
 * Creates two throwaway "e2e.*" accounts (disable afterwards with the admin CLI). Servers in invite-only
 * mode need GRIIP_INVITE: `docker compose ... exec api node dist/admin.js reg-invite 2 1 smoke-test`.
 */
import crypto from "node:crypto";
import { GriipClient } from "../support/griipClient.js";

const base = process.argv[2] ?? "https://msg.griipbuddy.cloud";
const tag = crypto.randomBytes(3).toString("hex");
const results: [string, boolean, string?][] = [];
const check = (name: string, ok: boolean, detail?: string) => {
  results.push([name, ok, detail]);
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  — " + detail : ""}`);
};

const a = new GriipClient(base, `e2e.a${tag}`, "E2E Alpha");
const b = new GriipClient(base, `e2e.b${tag}`, "E2E Bravo");
await a.register(process.env.GRIIP_INVITE);
await b.register(process.env.GRIIP_INVITE);
check("register two identities over TLS", !!a.userId && !!b.userId);

const dm = (await a.req("POST", "/v1/conversations/dm", { userId: b.userId })).conversation.id;
await a.connect();
await b.connect();
check("WebSocket through nginx (wss)", true);

const secret = `prod e2e ${crypto.randomUUID()}`;
const t0 = Date.now();
const sent = await a.send(dm, { type: "text", text: secret });
const got = await b.waitFor(() => b.received.find((m) => m.messageId === sent.messageId), 10000);
check("A→B encrypted delivery (realtime)", got.payload.text === secret, `${Date.now() - t0} ms`);
const receipt = await a.waitFor(() => a.receipts.find((r) => r.messageId === sent.messageId), 10000);
check("delivery receipt back to sender", receipt.payload.status === "delivered");
const back = await b.send(dm, { type: "text", text: "pong" });
await a.waitFor(() => a.received.find((m) => m.messageId === back.messageId), 10000);
check("B→A encrypted delivery", true);

await b.disconnect();
const offline = await a.send(dm, { type: "text", text: "while you were away" });
b.received = [];
await b.connect();
await b.waitFor(() => b.received.find((m) => m.messageId === offline.messageId), 10000);
check("offline queue delivered after reconnect", true);

const call = await a.req("POST", "/v1/calls", { conversationId: dm, video: false });
check("call created + LiveKit token issued", !!call.media?.token && call.media.url === "wss://rtc.griipbuddy.cloud", call.media?.url);
const ring = await b.waitFor(() => b.events.find((e) => e.t === "call" && e.event === "ring" && e.call.id === call.call.id), 10000);
check("callee receives ring event", !!ring);
await a.req("POST", `/v1/calls/${call.call.id}/end`);

await a.disconnect();
await b.disconnect();
console.log(`\nACCOUNTS: ${a.username} ${b.username}`);
const failed = results.filter((r) => !r[1]).length;
console.log(failed ? `${failed} FAILED` : "ALL PASSED");
process.exit(failed ? 1 : 0);
