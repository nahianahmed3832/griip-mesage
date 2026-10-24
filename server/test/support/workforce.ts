import type { ConsoleAdmin } from "./adminConsole.js";
import type { GriipClient } from "./griipClient.js";
import type { Harness } from "./harness.js";

/** Shared setup for the AI Workforce suites: the platform configured through the console API, and a community. */
export const REASON = "AI Workforce test setup";

export interface PlatformModel {
  model: string;
  capabilities: string[];
  inputPriceMicro?: number | null;
  outputPriceMicro?: number | null;
  qualityRank?: number;
}

/** A provider with models whose capabilities are turned on and verified, AI and AI Workforce on, [agents] published. */
export async function configurePlatform(admin: ConsoleAdmin, opts: { kind?: "openai" | "anthropic" | "google"; name?: string; models: PlatformModel[]; agents: string[] }): Promise<string> {
  const b = admin.browser;
  await b.expect(200, "PUT", "/admin/v1/settings/ai.enabled", { value: true, reason: REASON });
  const providerId = (await b.expect(200, "POST", "/admin/v1/ai/providers", { kind: opts.kind ?? "openai", name: opts.name ?? "Test provider", reason: REASON })).provider.id;
  await b.expect(200, "PUT", `/admin/v1/ai/providers/${providerId}/credential`, { apiKey: "sk-test-0123456789abcdef", reason: REASON });
  await b.expect(200, "PATCH", `/admin/v1/ai/providers/${providerId}`, { enabled: true, reason: REASON });
  for (const m of opts.models) {
    await b.expect(200, "PUT", `/admin/v1/ai/providers/${providerId}/models`, { model: m.model, enabled: true, inputPriceMicro: m.inputPriceMicro ?? null, outputPriceMicro: m.outputPriceMicro ?? null, priceSource: m.inputPriceMicro ? "test prices" : "", reason: REASON });
    await b.expect(200, "PUT", `/admin/v1/ai/providers/${providerId}/models/${m.model}/capabilities`, { capabilities: m.capabilities, qualityRank: m.qualityRank ?? null, reason: REASON });
    for (const c of m.capabilities) {
      const r = await b.expect(200, "POST", `/admin/v1/ai/providers/${providerId}/models/${m.model}/verify`, { capability: c, reason: REASON });
      if (!r.ok) throw new Error(`verification of ${m.model} ${c} failed: ${r.detail}`);
    }
  }
  await b.expect(200, "PUT", "/admin/v1/ai/workforce/controls", { workforceEnabled: true, reason: REASON });
  for (const a of opts.agents) await b.expect(200, "PUT", `/admin/v1/ai/catalog/${a}/1`, { status: "published", reason: REASON });
  return providerId;
}

export interface Community {
  orgId: string;
  channel: string;
  owner: GriipClient;
  member: GriipClient;
}

export async function community(h: Harness, name: string): Promise<Community> {
  const [owner, member] = await Promise.all([h.client(`${name}owner`), h.client(`${name}member`)]);
  const orgId = (await owner.req("POST", "/v1/orgs", { name, visibility: "private", joinPolicy: "invite" })).org.id;
  const channel = (await owner.req("POST", `/v1/orgs/${orgId}/channels`, { name: "work", scope: "ORG" })).channel.id;
  const code = (await owner.req("POST", `/v1/orgs/${orgId}/invites`, { role: "MEMBER", maxUses: 5 })).invite.code;
  await member.req("POST", "/v1/invites/accept", { code });
  await member.req("POST", `/v1/conversations/${channel}/join`).catch(() => undefined);
  return { orgId, channel, owner, member };
}

/** Installs [agentId] in the community and assigns it to its channel; returns the installation id. */
export async function install(c: Community, agentId: string, displayName?: string): Promise<string> {
  const id = (await c.owner.req("POST", `/v1/orgs/${c.orgId}/ai/agents`, { agentId, ...(displayName ? { displayName } : {}) })).agent.id;
  await c.owner.req("PUT", `/v1/orgs/${c.orgId}/ai/agents/${id}/channels/${c.channel}`, { activation: "mention" });
  return id;
}

export async function failure(p: Promise<unknown>): Promise<{ status: number; body: { error: { code: string; message: string } } }> {
  try {
    await p;
  } catch (e) {
    return e as { status: number; body: { error: { code: string; message: string } } };
  }
  throw new Error("expected a failure");
}
