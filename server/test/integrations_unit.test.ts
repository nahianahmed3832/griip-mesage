import crypto from "node:crypto";
import { describe, expect, it } from "vitest";
import { CONNECTORS, connectorDef, scopesFor } from "../src/integrations/catalog.js";
import { integrationsConfig } from "../src/integrations/config.js";
import { Kernel } from "../src/integrations/kernel.js";
import { allowedActions, connectorState, DEFAULT_WORKSPACE_POLICY } from "../src/integrations/policy.js";
import { parseGitHubEvent } from "../src/integrations/providers/github.js";
import { keyringFrom, open, seal, SealError } from "../src/integrations/tokens.js";
import { BASE_ROLE_PERMISSIONS } from "../src/permissions/catalog.js";
import { providerEnv } from "./support/fakeProviders.js";

/** Pure parts of the integrations framework: sealing, the catalog's honesty, states and configuration guards. */
const key = () => crypto.randomBytes(32).toString("base64");
const int = (_n: string, d: number) => d;

describe("token sealing", () => {
  it("opens only for the same purpose and record, and refuses tampering", () => {
    const ring = keyringFrom(key(), null)!;
    const s = seal(ring, "secret-token", "access-token", "acct_1");
    expect(s).not.toContain("secret-token");
    expect(open(ring, s, "access-token", "acct_1")).toBe("secret-token");
    expect(() => open(ring, s, "access-token", "acct_2")).toThrow(SealError);
    expect(() => open(ring, s, "refresh-token", "acct_1")).toThrow(SealError);
    const parts = s.split(".");
    const flipped = Buffer.from(parts[3], "base64url");
    flipped[0] ^= 1;
    expect(() => open(ring, [...parts.slice(0, 3), flipped.toString("base64url")].join("."), "access-token", "acct_1")).toThrow(SealError);
    expect(seal(ring, "same", "p", "r")).not.toBe(seal(ring, "same", "p", "r")); // fresh nonce each time
  });

  it("rotates keys: old values stay readable, new ones use the new key, removed keys fail closed", () => {
    const oldKey = key();
    const old = keyringFrom(oldKey, null)!;
    const sealedOld = seal(old, "t", "p", "r");
    const rotated = keyringFrom(key(), oldKey)!;
    expect(open(rotated, sealedOld, "p", "r")).toBe("t");
    expect(seal(rotated, "t", "p", "r").split(".")[1]).toBe(rotated.active.id);
    expect(() => open(keyringFrom(key(), null)!, sealedOld, "p", "r")).toThrow(SealError);
  });

  it("refuses keys that aren't 32 bytes", () => {
    expect(() => keyringFrom(Buffer.alloc(16).toString("base64"), null)).toThrow(/32 random bytes/);
  });
});

describe("catalog honesty", () => {
  const kernel = new Kernel({
    config: integrationsConfig({ ...providerEnv(), INTEGRATIONS_SANDBOX: "on", INTEGRATIONS_PUBLIC_URL: "http://meldiq.test", INTEGRATIONS_TOKEN_KEY: key() }, "development", int),
    fetch: async () => new Response("{}"),
    now: Date.now,
  } as never);

  it("implements every capability an available connector declares", () => {
    for (const def of CONNECTORS.filter((d) => d.availability !== "coming_soon")) {
      const c = kernel.connectors.get(def.id)!;
      expect([def.id, !!c]).toEqual([def.id, true]);
      const implemented = new Set<string>([
        c.search?.capability,
        c.browse?.capability,
        c.get?.capability,
        c.details?.capability,
        c.unfurlCapability,
        ...Object.values(c.actions ?? {}).map((a) => a.capability),
      ].filter((x): x is string => !!x));
      for (const cap of def.capabilities) {
        // Notifications come from verified webhooks and a linked connection, or from Meldiq's own incoming webhooks (src/integrations/hooks.ts).
        if (cap.kind === "notify") expect([def.id, cap.key, (!!c.webhook && !!c.linking) || def.id === "incoming-webhooks"]).toEqual([def.id, cap.key, true]);
        else if (!["files.link", "events.share", "pages.link"].includes(cap.key) || !c.get) expect([def.id, cap.key, implemented.has(cap.key)]).toEqual([def.id, cap.key, true]);
      }
    }
  });

  it("claims nothing for planned connectors", () => {
    for (const def of CONNECTORS.filter((d) => d.availability === "coming_soon")) {
      expect([def.id, def.capabilities.length, kernel.connectors.has(def.id)]).toEqual([def.id, 0, false]);
    }
  });

  it("never asks for broad scopes", () => {
    const all = CONNECTORS.flatMap((d) => d.capabilities.flatMap((c) => c.scopes));
    for (const s of ["https://www.googleapis.com/auth/drive", "https://www.googleapis.com/auth/drive.readonly", "https://www.googleapis.com/auth/calendar", "https://mail.google.com/"]) expect(all).not.toContain(s);
    expect(all.some((s) => /gmail|ReadWrite|Mail\.|manage:jira/i.test(s))).toBe(false);
    expect(scopesFor(connectorDef("google-drive")!, ["files.search", "files.browse"], "member")).toEqual(["email", "https://www.googleapis.com/auth/drive.metadata.readonly", "openid"]);
    expect(scopesFor(connectorDef("google-drive")!, ["files.search"], "workspace")).toEqual([]);
  });

  it("shows the sandbox only where it's enabled", () => {
    expect(kernel.visibleDefs().some((d) => d.id === "meldiq-sandbox")).toBe(true);
    const off = new Kernel({ config: integrationsConfig({}, "development", int), fetch: async () => new Response("{}"), now: Date.now } as never);
    expect(off.visibleDefs().some((d) => d.id === "meldiq-sandbox")).toBe(false);
  });
});

describe("states and actions", () => {
  const def = connectorDef("google-drive")!;
  const viewer = { perms: BASE_ROLE_PERMISSIONS.MEMBER, roleIds: new Set<string>(), connected: false, accountError: false };
  const cfg = { access: { mode: "everyone" as const, roleIds: [] } };
  const install = (state: string) => ({ state }) as never;

  it("puts the most blocking reason first", () => {
    expect(connectorState(connectorDef("gmail")!, { configured: true, blocked: false }, DEFAULT_WORKSPACE_POLICY, null, viewer, cfg)).toBe("coming_soon");
    expect(connectorState(def, { configured: false, blocked: false }, DEFAULT_WORKSPACE_POLICY, install("installed"), viewer, cfg)).toBe("needs_configuration");
    expect(connectorState(def, { configured: true, blocked: true }, DEFAULT_WORKSPACE_POLICY, install("installed"), viewer, cfg)).toBe("blocked");
    expect(connectorState(def, { configured: true, blocked: false }, { ...DEFAULT_WORKSPACE_POLICY, blockUnlisted: true }, null, viewer, cfg)).toBe("blocked");
    expect(connectorState(def, { configured: true, blocked: false }, DEFAULT_WORKSPACE_POLICY, install("requested"), viewer, cfg)).toBe("approval_required");
    expect(connectorState(def, { configured: true, blocked: false }, DEFAULT_WORKSPACE_POLICY, install("installed"), { ...viewer, connected: true }, cfg)).toBe("connected");
    expect(connectorState(def, { configured: true, blocked: false }, DEFAULT_WORKSPACE_POLICY, install("installed"), { ...viewer, accountError: true }, cfg)).toBe("error");
    expect(connectorState(def, { configured: true, blocked: false }, DEFAULT_WORKSPACE_POLICY, install("installed"), viewer, { access: { mode: "roles", roleIds: ["r1"] } })).toBe("blocked");
  });

  it("offers each person only what they may do", () => {
    expect(allowedActions(def, "available", DEFAULT_WORKSPACE_POLICY, viewer)).toEqual(["request"]);
    expect(allowedActions(def, "available", { ...DEFAULT_WORKSPACE_POLICY, requestsEnabled: false }, viewer)).toEqual([]);
    expect(allowedActions(def, "available", DEFAULT_WORKSPACE_POLICY, { ...viewer, perms: BASE_ROLE_PERMISSIONS.ADMIN })).toEqual(["install"]);
    expect(allowedActions(def, "installed", DEFAULT_WORKSPACE_POLICY, viewer)).toEqual(["connect"]);
    expect(allowedActions(def, "installed", DEFAULT_WORKSPACE_POLICY, { ...viewer, perms: BASE_ROLE_PERMISSIONS.GUEST })).toEqual([]);
    expect(allowedActions(def, "connected", DEFAULT_WORKSPACE_POLICY, { ...viewer, perms: BASE_ROLE_PERMISSIONS.ADMIN })).toEqual(["disconnect", "configure", "uninstall"]);
  });

  it("offers linking only for connectors that link a workspace (not incoming webhooks)", () => {
    const admin = { ...viewer, perms: BASE_ROLE_PERMISSIONS.ADMIN };
    const hooks = connectorDef("incoming-webhooks")!;
    expect(allowedActions(hooks, "installed", DEFAULT_WORKSPACE_POLICY, admin, false)).toEqual(["configure", "uninstall"]);
    const sandbox = connectorDef("meldiq-sandbox")!;
    expect(allowedActions(sandbox, "installed", DEFAULT_WORKSPACE_POLICY, admin, true)).toContain("link_workspace");
  });
});

describe("configuration guards", () => {
  it("needs https and refuses the sandbox in production", () => {
    expect(() => integrationsConfig({ INTEGRATIONS_PUBLIC_URL: "http://msg.example.com" }, "production", int)).toThrow(/https/);
    expect(() => integrationsConfig({ INTEGRATIONS_PUBLIC_URL: "https://msg.example.com", INTEGRATIONS_SANDBOX: "on" }, "production", int)).toThrow(/never production/);
    expect(() => integrationsConfig({ MICROSOFT_OAUTH_TENANT: "evil/../x" }, "development", int)).toThrow(/MICROSOFT_OAUTH_TENANT/);
  });

  it("uses the API's public address for sign-ins unless INTEGRATIONS_PUBLIC_URL says otherwise", () => {
    expect(integrationsConfig({ PUBLIC_API_URL: "https://api.example.com/" }, "production", int).publicUrl).toBe("https://api.example.com");
    expect(integrationsConfig({ PUBLIC_API_URL: "https://api.example.com", INTEGRATIONS_PUBLIC_URL: "https://apps.example.com" }, "production", int).publicUrl).toBe("https://apps.example.com");
    expect(() => integrationsConfig({ PUBLIC_API_URL: "http://api.example.com" }, "production", int)).toThrow(/https/);
  });

  it("reports what's missing by name only", () => {
    const c = integrationsConfig({ GOOGLE_OAUTH_CLIENT_ID: "x" }, "development", int);
    expect(c.google).toBeNull();
    expect(c.missing.google).toEqual(["INTEGRATIONS_TOKEN_KEY", "INTEGRATIONS_PUBLIC_URL", "GOOGLE_OAUTH_CLIENT_SECRET"]);
  });
});

describe("GitHub event parsing", () => {
  it("keeps only titles, links and actors, and ignores what it doesn't know", () => {
    const base = { installation: { id: 5 }, repository: { full_name: "a/b" }, sender: { login: "dev" } };
    const run = parseGitHubEvent("workflow_run", { ...base, action: "completed", workflow_run: { id: 1, name: "CI", conclusion: "failure", head_branch: "main", html_url: "https://github.com/a/b/actions/runs/1", secrets: "nope" } });
    expect(run).toEqual({ externalId: "5", events: [expect.objectContaining({ event: "workflow_run.failure", title: "CI failed on main", resource: "a/b" })] });
    expect(JSON.stringify(run)).not.toContain("nope");
    expect(parseGitHubEvent("push", { ...base, commits: [{ message: "secret" }] })).toEqual({ externalId: "5", events: [] });
    expect(parseGitHubEvent("pull_request", { action: "opened", repository: { full_name: "a/b" } })).toBeNull();
    expect(parseGitHubEvent("issues", { ...base, repository: { full_name: "../../x" }, action: "opened", issue: { number: 1 } })!.events).toEqual([]);
  });
});
