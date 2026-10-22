import { describe, expect, it } from "vitest";
import { callExtras, isRelayedCallEvent } from "../src/modules/federation/service.js";

/** Call events relayed by a peer server reach this server's clients only in the contract's shape (src/shared/contracts/realtime.ts). */
describe("realtime contract: call events from peer servers", () => {
  it("accepts only the relayed call events", () => {
    for (const e of ["ring", "updated", "ended", "alerted", "declined", "dismissed", "started"]) expect(isRelayedCallEvent(e)).toBe(true);
    for (const e of ["moderated", "RING", "", null, 7, undefined]) expect(isRelayedCallEvent(e)).toBe(false);
  });

  it("keeps the contract's extra fields with their types", () => {
    expect(callExtras({ from: { userId: "u_1", displayName: "Ada" } })).toEqual({ from: { userId: "u_1", displayName: "Ada" } });
    expect(callExtras({ userId: "u_2", reason: "busy" })).toEqual({ userId: "u_2", reason: "busy" });
  });

  it("drops unknown fields and wrongly typed ones", () => {
    expect(callExtras({ t: "org.deleted", orgId: "o_1", call: {}, admin: true })).toEqual({});
    expect(callExtras({ from: { userId: 5, displayName: "x" }, userId: 3, reason: "angry" })).toEqual({});
    expect(callExtras(null)).toEqual({});
    expect(callExtras("ring")).toEqual({});
  });
});
