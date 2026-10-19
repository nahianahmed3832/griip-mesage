import type { RowStore, Where } from "../store/rows.js";

/**
 * The integrations domain's records (migration 016; docs/INTEGRATIONS.md › Data model), on SQLite or MongoDB through
 * [RowStore]. Every read is scoped by workspace where it can be, and every check-and-write is one conditional update
 * (revision, state or "not used yet"), so concurrent installs, connects and callbacks can't both win on either store.
 */
export interface InstallRow {
  [k: string]: string | number | null;
  id: string;
  workspace_id: string;
  connector_id: string;
  connector_version: number;
  state: InstallState;
  requested_caps: string;
  granted_caps: string;
  config: string;
  requested_by: string | null;
  request_note: string | null;
  installed_by: string | null;
  decided_by: string | null;
  decided_at: number | null;
  rev: number;
  created_at: number;
  updated_at: number;
  last_event_at: number | null;
  last_error: string | null;
  last_error_at: number | null;
}
export type InstallState = "requested" | "installed" | "disabled" | "rejected" | "uninstalled";

export interface AccountRow {
  [k: string]: string | number | null;
  id: string;
  workspace_id: string;
  connector_id: string;
  owner_type: "member" | "workspace";
  owner_key: string;
  user_id: string;
  external_id: string;
  external_label: string | null;
  scopes: string;
  meta: string;
  status: AccountStatus;
  rev: number;
  created_at: number;
  updated_at: number;
  last_validated_at: number | null;
  last_used_at: number | null;
  last_error: string | null;
  last_error_at: number | null;
}
export type AccountStatus = "active" | "expired" | "revoked" | "error";

export interface CredentialRow {
  [k: string]: string | number | null;
  account_id: string;
  key_id: string;
  access_token: string;
  refresh_token: string | null;
  expires_at: number | null;
  updated_at: number;
}

export interface OAuthStateRow {
  [k: string]: string | number | null;
  id: string;
  state_hash: string;
  begin_hash: string | null;
  browser_hash: string | null;
  finish_hash: string | null;
  workspace_id: string;
  connector_id: string;
  user_id: string;
  owner_type: "member" | "workspace";
  verifier: string | null;
  redirect_uri: string;
  scopes: string;
  created_at: number;
  expires_at: number;
  used_at: number | null;
  result: "approved" | "finishing" | "connected" | "denied" | "failed" | null;
  error: string | null;
  account_id: string | null;
}

export interface SubscriptionRow {
  [k: string]: string | number | null;
  id: string;
  workspace_id: string;
  connector_id: string;
  account_id: string;
  conversation_id: string;
  resource: string;
  events: string;
  created_by: string;
  created_at: number;
  updated_at: number;
  active: number;
  last_delivery_at: number | null;
  last_error: string | null;
}

export interface JobRow {
  [k: string]: string | number | null;
  id: string;
  kind: string;
  workspace_id: string | null;
  idempotency_key: string;
  payload: string;
  state: "queued" | "running" | "done" | "dead";
  attempts: number;
  next_at: number;
  lease_until: number | null;
  last_error: string | null;
  created_at: number;
  updated_at: number;
}

export interface ChannelEventRow {
  [k: string]: string | number | null;
  id: string;
  workspace_id: string;
  conversation_id: string;
  connector_id: string;
  subscription_id: string;
  kind: string;
  title: string;
  url: string | null;
  summary: string | null;
  actor: string | null;
  occurred_at: number;
  created_at: number;
  expires_at: number;
  dedupe_key: string;
}

export interface HookRow {
  [k: string]: string | number | null;
  id: string;
  workspace_id: string;
  conversation_id: string;
  name: string;
  secret: string;
  created_by: string;
  created_at: number;
  updated_at: number;
  active: number;
  last_delivery_at: number | null;
  last_error: string | null;
}

export interface PolicyRow {
  [k: string]: string | number | null;
  id: string;
  doc: string;
  rev: number;
  updated_by: string | null;
  updated_at: number;
}

const T = {
  installs: "integration_installs",
  accounts: "integration_accounts",
  creds: "integration_credentials",
  states: "integration_oauth_states",
  subs: "integration_subscriptions",
  deliveries: "integration_deliveries",
  jobs: "integration_jobs",
  events: "integration_channel_events",
  policies: "integration_policies",
  hooks: "integration_hooks",
} as const;

export const INTEGRATION_TABLES: readonly string[] = Object.values(T);

export class IntegrationStore {
  constructor(readonly rows: RowStore) {}

  get backend(): "sqlite" | "mongodb" {
    return this.rows.backend;
  }

  // ───── Installs ─────
  install(workspaceId: string, connectorId: string): Promise<InstallRow | null> {
    return this.rows.first<InstallRow>(T.installs, { workspace_id: workspaceId, connector_id: connectorId });
  }
  installs(workspaceId: string): Promise<InstallRow[]> {
    return this.rows.find<InstallRow>(T.installs, { workspace_id: workspaceId }, { orderBy: [["connector_id", "asc"]] });
  }
  insertInstall(row: InstallRow): Promise<boolean> {
    return this.rows.insert(T.installs, row);
  }
  /** Changes the install only if it is still at [rev]; false when someone else changed it first. */
  async updateInstall(id: string, rev: number, set: Partial<InstallRow>): Promise<boolean> {
    return (await this.rows.update(T.installs, { id, rev }, { ...set, rev: rev + 1 } as InstallRow)) === 1;
  }
  async touchInstall(id: string, set: Pick<Partial<InstallRow>, "last_event_at" | "last_error" | "last_error_at">): Promise<void> {
    await this.rows.update(T.installs, { id }, set as InstallRow);
  }

  // ───── Accounts and credentials ─────
  account(id: string): Promise<AccountRow | null> {
    return this.rows.first<AccountRow>(T.accounts, { id });
  }
  accountFor(workspaceId: string, connectorId: string, ownerKey: string): Promise<AccountRow | null> {
    return this.rows.first<AccountRow>(T.accounts, { workspace_id: workspaceId, connector_id: connectorId, owner_key: ownerKey });
  }
  accounts(where: { workspace_id: string; connector_id?: string; user_id?: string; owner_type?: string }): Promise<AccountRow[]> {
    return this.rows.find<AccountRow>(T.accounts, where as Where, { orderBy: [["created_at", "asc"]] });
  }
  /** Workspace connections a provider's delivery is for (GitHub installation id, sandbox organisation). */
  workspaceConnections(connectorId: string, externalId: string): Promise<AccountRow[]> {
    return this.rows.find<AccountRow>(T.accounts, { connector_id: connectorId, owner_type: "workspace", external_id: externalId });
  }
  /** Connections of one provider account (its stable id) through any of [connectorIds], in every workspace. */
  accountsWithExternalId(connectorIds: string[], externalId: string): Promise<AccountRow[]> {
    return this.rows.find<AccountRow>(T.accounts, { external_id: externalId, connector_id: { in: connectorIds } });
  }
  accountsOfUser(userId: string): Promise<AccountRow[]> {
    return this.rows.find<AccountRow>(T.accounts, { user_id: userId });
  }
  insertAccount(row: AccountRow): Promise<boolean> {
    return this.rows.insert(T.accounts, row);
  }
  async updateAccount(id: string, rev: number | null, set: Partial<AccountRow>): Promise<boolean> {
    const where: Where = rev === null ? { id } : { id, rev };
    const next = rev === null ? set : { ...set, rev: rev + 1 };
    return (await this.rows.update(T.accounts, where, next as AccountRow)) === 1;
  }
  async deleteAccount(id: string): Promise<void> {
    await this.rows.delete(T.creds, { account_id: id });
    await this.rows.delete(T.subs, { account_id: id });
    await this.rows.delete(T.accounts, { id });
  }
  credentials(accountId: string): Promise<CredentialRow | null> {
    return this.rows.first<CredentialRow>(T.creds, { account_id: accountId });
  }
  async putCredentials(row: CredentialRow): Promise<void> {
    const { account_id, ...rest } = row;
    if ((await this.rows.update(T.creds, { account_id }, rest as CredentialRow)) === 0 && !(await this.rows.insert(T.creds, row))) {
      // Inserted by a concurrent refresh between the two statements: write ours over it (the newest token wins).
      await this.rows.update(T.creds, { account_id }, rest as CredentialRow);
    }
  }
  /** Replaces the token only if it is still the one [previousAccess] read (two refreshes at once: one wins). */
  async swapCredentials(accountId: string, previousAccess: string, set: Omit<CredentialRow, "account_id">): Promise<boolean> {
    return (await this.rows.update(T.creds, { account_id: accountId, access_token: previousAccess }, set as CredentialRow)) === 1;
  }
  async deleteCredentials(accountId: string): Promise<void> {
    await this.rows.delete(T.creds, { account_id: accountId });
  }

  // ───── Authorisations in progress ─────
  insertState(row: OAuthStateRow): Promise<boolean> {
    return this.rows.insert(T.states, row);
  }
  state(id: string): Promise<OAuthStateRow | null> {
    return this.rows.first<OAuthStateRow>(T.states, { id });
  }
  stateByHash(stateHash: string): Promise<OAuthStateRow | null> {
    return this.rows.first<OAuthStateRow>(T.states, { state_hash: stateHash });
  }
  /** The browser opened the begin link: single use, and binds the flow to that browser's cookie. */
  async claimBegin(id: string, beginHash: string, browserHash: string, now: number): Promise<boolean> {
    return (await this.rows.update(T.states, { id, begin_hash: beginHash, used_at: null, expires_at: { gt: now } }, { begin_hash: null, browser_hash: browserHash })) === 1;
  }
  /** The provider called back: single use, whatever the outcome. */
  async claimCallback(id: string, now: number): Promise<boolean> {
    return (await this.rows.update(T.states, { id, used_at: null, expires_at: { gt: now } }, { used_at: now })) === 1;
  }
  /** The provider approved: its code waits, sealed in [verifier], until the person who started the sign-in finishes it. */
  async approveState(id: string, verifier: string, finishHash: string, expiresAt: number): Promise<boolean> {
    return (await this.rows.update(T.states, { id, result: null }, { result: "approved", verifier, finish_hash: finishHash, expires_at: expiresAt })) === 1;
  }
  /** The app finishes an approved sign-in: single use, before it expires. */
  async claimFinish(id: string, finishHash: string, now: number): Promise<boolean> {
    return (await this.rows.update(T.states, { id, result: "approved", finish_hash: finishHash, expires_at: { gt: now } }, { result: "finishing", finish_hash: null })) === 1;
  }
  async finishState(id: string, result: "connected" | "denied" | "failed", error: string | null, accountId: string | null): Promise<void> {
    await this.rows.update(T.states, { id }, { result, error, account_id: accountId, verifier: null, finish_hash: null });
  }

  // ───── Subscriptions ─────
  subscriptions(where: { workspace_id?: string; account_id?: string; connector_id?: string; conversation_id?: string }): Promise<SubscriptionRow[]> {
    return this.rows.find<SubscriptionRow>(T.subs, where as Where, { orderBy: [["created_at", "asc"]] });
  }
  subscription(id: string): Promise<SubscriptionRow | null> {
    return this.rows.first<SubscriptionRow>(T.subs, { id });
  }
  insertSubscription(row: SubscriptionRow): Promise<boolean> {
    return this.rows.insert(T.subs, row);
  }
  async updateSubscription(id: string, set: Partial<SubscriptionRow>): Promise<void> {
    await this.rows.update(T.subs, { id }, set as SubscriptionRow);
  }
  async deleteSubscription(id: string): Promise<boolean> {
    return (await this.rows.delete(T.subs, { id })) > 0;
  }

  // ───── Webhook deliveries ─────
  /** False when this delivery id was accepted before (a duplicate or a replay). */
  acceptDelivery(id: string, connectorId: string, event: string, now: number, expiresAt: number): Promise<boolean> {
    return this.rows.insert(T.deliveries, { id, connector_id: connectorId, event, status: "queued", received_at: now, expires_at: expiresAt });
  }
  async markDelivery(id: string, status: "done" | "ignored" | "failed"): Promise<void> {
    await this.rows.update(T.deliveries, { id }, { status });
  }
  /** Forgets an accepted delivery whose job couldn't be queued, so the provider's redelivery is processed. */
  async releaseDelivery(id: string): Promise<void> {
    await this.rows.delete(T.deliveries, { id, status: "queued" });
  }

  // ───── Jobs ─────
  /** Queues a job once per idempotency key; a second enqueue with the same key changes nothing. */
  enqueue(row: JobRow): Promise<boolean> {
    return this.rows.insert(T.jobs, row);
  }
  job(id: string): Promise<JobRow | null> {
    return this.rows.first<JobRow>(T.jobs, { id });
  }
  /** Claims up to [limit] due jobs with a lease; a crashed worker's jobs come back when their lease runs out. */
  async claimJobs(now: number, leaseMs: number, limit: number): Promise<JobRow[]> {
    await this.rows.update(T.jobs, { state: "running", lease_until: { lt: now } }, { state: "queued", lease_until: null });
    const due = await this.rows.find<JobRow>(T.jobs, { state: "queued", next_at: { lte: now } }, { orderBy: [["next_at", "asc"]], limit });
    const claimed: JobRow[] = [];
    for (const j of due) {
      const ok = (await this.rows.update(T.jobs, { id: j.id, state: "queued", attempts: j.attempts }, { state: "running", lease_until: now + leaseMs, attempts: j.attempts + 1, updated_at: now })) === 1;
      if (ok) claimed.push({ ...j, state: "running", attempts: j.attempts + 1, lease_until: now + leaseMs });
    }
    return claimed;
  }
  async finishJob(id: string, now: number): Promise<void> {
    await this.rows.update(T.jobs, { id }, { state: "done", lease_until: null, updated_at: now, last_error: null });
  }
  async retryJob(id: string, nextAt: number | null, error: string, now: number): Promise<void> {
    await this.rows.update(T.jobs, { id }, nextAt === null ? { state: "dead", lease_until: null, last_error: error, updated_at: now } : { state: "queued", next_at: nextAt, lease_until: null, last_error: error, updated_at: now });
  }
  jobs(where: { workspace_id?: string; state?: string }, limit: number): Promise<JobRow[]> {
    return this.rows.find<JobRow>(T.jobs, where as Where, { orderBy: [["updated_at", "desc"]], limit });
  }

  // ───── Channel notifications ─────
  /** False when this provider change was already posted to this channel (same dedupe key). */
  insertEvent(row: ChannelEventRow): Promise<boolean> {
    return this.rows.insert(T.events, row);
  }
  events(conversationId: string, after: number, limit: number): Promise<ChannelEventRow[]> {
    return this.rows.find<ChannelEventRow>(T.events, { conversation_id: conversationId, created_at: { gt: after } }, { orderBy: [["created_at", "asc"]], limit });
  }
  recentEvents(workspaceId: string, limit: number): Promise<ChannelEventRow[]> {
    return this.rows.find<ChannelEventRow>(T.events, { workspace_id: workspaceId }, { orderBy: [["created_at", "desc"]], limit });
  }

  // ───── Policies ─────
  policy(id: string): Promise<PolicyRow | null> {
    return this.rows.first<PolicyRow>(T.policies, { id });
  }
  /** Writes [doc] if the stored revision is still [baseRev] (0: none stored yet). */
  async putPolicy(id: string, doc: string, baseRev: number, by: string | null, now: number): Promise<boolean> {
    if (baseRev === 0) return this.rows.insert(T.policies, { id, doc, rev: 1, updated_by: by, updated_at: now });
    return (await this.rows.update(T.policies, { id, rev: baseRev }, { doc, rev: baseRev + 1, updated_by: by, updated_at: now })) === 1;
  }

  // ───── Incoming webhooks ─────
  hook(id: string): Promise<HookRow | null> {
    return this.rows.first<HookRow>(T.hooks, { id });
  }
  hooks(workspaceId: string): Promise<HookRow[]> {
    return this.rows.find<HookRow>(T.hooks, { workspace_id: workspaceId }, { orderBy: [["created_at", "asc"]] });
  }
  insertHook(row: HookRow): Promise<boolean> {
    return this.rows.insert(T.hooks, row);
  }
  async updateHook(id: string, set: Partial<HookRow>): Promise<void> {
    await this.rows.update(T.hooks, { id }, set as HookRow);
  }
  async deleteHook(id: string): Promise<boolean> {
    return (await this.rows.delete(T.hooks, { id })) > 0;
  }

  // ───── Retention and removal ─────
  async purge(now: number, jobCutoff: number): Promise<number> {
    let n = 0;
    n += await this.rows.delete(T.states, { expires_at: { lt: now - 86_400_000 } });
    n += await this.rows.delete(T.deliveries, { expires_at: { lt: now } });
    n += await this.rows.delete(T.events, { expires_at: { lt: now } });
    n += await this.rows.delete(T.jobs, { state: { in: ["done", "dead"] }, updated_at: { lt: jobCutoff } });
    return n;
  }

  /** Everything of a deleted workspace (accounts' credentials included). */
  async forgetWorkspace(workspaceId: string): Promise<void> {
    for (const a of await this.accounts({ workspace_id: workspaceId })) await this.rows.delete(T.creds, { account_id: a.id });
    for (const t of [T.accounts, T.installs, T.subs, T.states, T.events, T.jobs, T.hooks] as const) await this.rows.delete(t, { workspace_id: workspaceId });
    await this.rows.delete(T.policies, { id: workspaceId });
  }

  async deleteEventsForConversation(conversationId: string): Promise<void> {
    await this.rows.delete(T.events, { conversation_id: conversationId });
    await this.rows.delete(T.subs, { conversation_id: conversationId });
    await this.rows.delete(T.hooks, { conversation_id: conversationId });
  }
}
