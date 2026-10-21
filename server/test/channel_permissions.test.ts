import { describe, expect, it } from "vitest";
import { BASE_ROLE_PERMISSIONS, type Permission } from "../src/permissions/catalog.js";
import { type ChannelActor, channelPermissions, type Overwrite, overwriteRefusal, parseCapabilities } from "../src/permissions/channel.js";

const actor = (base: ChannelActor["base"], roleIds: string[] = [], extra: Permission[] = [], restricted: Permission[] = []): ChannelActor => ({
  base,
  userId: "u1",
  perms: new Set([...BASE_ROLE_PERMISSIONS[base], ...extra]),
  roleIds,
  restricted: new Set(restricted),
});
const ow = (subject: string, allow: Overwrite["allow"] = [], deny: Overwrite["deny"] = []): Overwrite => ({ subject, allow, deny });

describe("channel permissions", () => {
  it("start from the community permissions when nothing overrides them", () => {
    const p = channelPermissions(actor("MEMBER"), [[], []]);
    expect(p.has("channels.view")).toBe(true);
    expect(p.has("messages.post")).toBe(true);
    expect(p.has("channels.manage")).toBe(false);
  });

  it("apply @everyone, then roles, then the member, deny before allow, category before channel", () => {
    const category = [ow("everyone", [], ["channels.view"]), ow("staff", ["channels.view"])];
    expect(channelPermissions(actor("MEMBER"), [category, []]).has("channels.view")).toBe(false);
    expect(channelPermissions(actor("MEMBER", ["staff"]), [category, []]).has("channels.view")).toBe(true);
    // A role's deny and another role's allow: allow wins at the same level (allows come after denies).
    const both = [ow("muted", [], ["messages.post"]), ow("helper", ["messages.post"])];
    expect(channelPermissions(actor("MEMBER", ["muted", "helper"]), [both]).has("messages.post")).toBe(true);
    // The member's own overwrite beats their roles.
    const personal = [ow("staff", ["channels.view"]), ow("user:u1", [], ["channels.view"])];
    expect(channelPermissions(actor("MEMBER", ["staff"]), [personal]).has("channels.view")).toBe(false);
    // The channel's own overwrite beats the category's.
    expect(channelPermissions(actor("MEMBER"), [category, [ow("everyone", ["channels.view"])]]).has("channels.view")).toBe(true);
  });

  it("let owners and admins skip overwrites", () => {
    const lockdown = [ow("everyone", [], ["channels.view", "messages.post"])];
    expect(channelPermissions(actor("ADMIN"), [lockdown]).has("channels.view")).toBe(true);
    expect(channelPermissions(actor("OWNER"), [lockdown]).has("messages.post")).toBe(true);
  });

  it("never lift a role's community-wide restriction", () => {
    const allowPost = [ow("everyone", ["messages.post"])];
    const p = channelPermissions(actor("MEMBER", [], [], ["messages.post"]), [allowPost]);
    expect(p.has("messages.post")).toBe(false);
  });

  it("give guests only guest-safe powers, whatever an overwrite says", () => {
    const generous = [ow("everyone", ["channels.manage", "messages.announce", "messages.attach"])];
    const p = channelPermissions(actor("GUEST"), [generous]);
    expect(p.has("channels.manage")).toBe(false);
    expect(p.has("messages.announce")).toBe(false);
    expect(p.has("messages.attach")).toBe(true);
  });

  it("ignore capability keys it doesn't know (a newer server's)", () => {
    expect(parseCapabilities('["messages.post","messages.fly"]')).toEqual(["messages.post"]);
    expect(parseCapabilities("not json")).toEqual([]);
  });
});

describe("writing overwrites", () => {
  const held = new Set<Permission>(["messages.post", "messages.attach", "channels.view", "channels.manage"]);
  it("refuses allowing and denying the same thing", () => {
    expect(overwriteRefusal(held, ow("everyone", ["messages.post"], ["messages.post"]))?.code).toBe("allow_and_deny");
  });
  it("refuses changing a power the writer doesn't hold, and allows leaving it as it was", () => {
    expect(overwriteRefusal(held, ow("everyone", ["messages.announce"]))?.code).toBe("beyond_your_powers");
    const prev = ow("everyone", ["messages.announce"]);
    expect(overwriteRefusal(held, ow("everyone", ["messages.announce"], ["messages.attach"]), prev)).toBeNull();
    // Removing someone else's allow of a power you lack is a change too.
    expect(overwriteRefusal(held, ow("everyone"), prev)?.code).toBe("beyond_your_powers");
  });
});
