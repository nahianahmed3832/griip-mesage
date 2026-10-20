import type { AppContext } from "./context.js";

/**
 * Profiles and who may see each part of them (2026-10-17, docs/PROFILE_AND_PRIVACY.md).
 *
 * A profile is information a person chooses to publish. Each optional field has an audience:
 * - "everyone": anyone signed in to this server who can look the person up;
 * - "contacts": people who share a conversation with them, or a workspace where neither is a guest;
 * - "nobody": only the person themself.
 * The username, display name and avatar colour are always visible (conversations need them). Someone the person has
 * blocked sees only those. Birthday and phone start as "nobody", last seen as "contacts", the rest as "everyone".
 */
export type Audience = "everyone" | "contacts" | "nobody";
export const AUDIENCES: readonly Audience[] = ["everyone", "contacts", "nobody"];

export const PRIVACY_DEFAULTS = {
  photo: "everyone",
  bio: "everyone",
  name: "everyone",
  birthday: "nobody",
  phone: "nobody",
  lastSeen: "contacts",
  /** Who can find the person by their exact phone number (never by address book upload). */
  findByPhone: "nobody",
  /** Who can find the person by their exact username (people who don't share anything with them yet need "everyone"). */
  findByUsername: "everyone",
} satisfies Record<string, Audience>;

export type PrivacyField = keyof typeof PRIVACY_DEFAULTS;
export type Privacy = Record<PrivacyField, Audience>;
export const PRIVACY_FIELDS = Object.keys(PRIVACY_DEFAULTS) as PrivacyField[];

export interface ProfileRow {
  id: string;
  username: string;
  display_name: string;
  avatar_color: number;
  status_text: string | null;
  status_emoji?: string | null;
  status_expires_at?: number | null;
  notifications_paused_until?: number | null;
  first_name?: string | null;
  last_name?: string | null;
  birthday?: string | null;
  phone?: string | null;
  photo_rev?: number | null;
  privacy?: string | null;
  home_server_id?: string | null;
}

/** The person's privacy settings: stored values over the defaults; anything unreadable falls back to the default. */
export function privacyOf(u: { privacy?: string | null }): Privacy {
  const out: Privacy = { ...PRIVACY_DEFAULTS };
  if (!u.privacy) return out;
  try {
    const stored = JSON.parse(u.privacy) as Record<string, unknown>;
    for (const f of PRIVACY_FIELDS) {
      const v = stored[f];
      if (typeof v === "string" && (AUDIENCES as readonly string[]).includes(v)) out[f] = v as Audience;
    }
  } catch {
    /* defaults */
  }
  return out;
}

/** How the viewer relates to the person whose profile it is. */
export interface Relation {
  self: boolean;
  contact: boolean;
  /** The person has blocked the viewer. */
  blockedByTarget: boolean;
}

export function relation(ctx: AppContext, viewerId: string, targetId: string): Relation {
  if (viewerId === targetId) return { self: true, contact: true, blockedByTarget: false };
  const blocked = !!ctx.db.prepare("SELECT 1 FROM user_blocks WHERE user_id = ? AND blocked_user_id = ?").get(targetId, viewerId);
  return { self: false, contact: ctx.authz.canSeePresence(viewerId, targetId), blockedByTarget: blocked };
}

export function allows(audience: Audience, r: Relation): boolean {
  if (r.self) return true;
  if (r.blockedByTarget) return false;
  return audience === "everyone" || (audience === "contacts" && r.contact);
}

/**
 * A person's status as others see it right now (round 3): text and emoji until its end time, then nothing. The row
 * isn't cleared when it expires; every view applies the end time, so an expired status never shows.
 */
export function activeStatus(
  u: Pick<ProfileRow, "status_text" | "status_emoji" | "status_expires_at">,
  at = Date.now(),
): { text: string | null; emoji: string | null; expiresAt: number | null } {
  const expired = u.status_expires_at != null && u.status_expires_at <= at;
  if (expired) return { text: null, emoji: null, expiresAt: null };
  return { text: u.status_text ?? null, emoji: u.status_emoji ?? null, expiresAt: u.status_expires_at ?? null };
}

/** Whether the person has paused notifications right now (round 3). */
export function notificationsPaused(u: Pick<ProfileRow, "notifications_paused_until">, at = Date.now()): boolean {
  return u.notifications_paused_until != null && u.notifications_paused_until > at;
}

/**
 * The profile [r]'s viewer may see. Fields they may not see are absent (not null), so "hidden" and "empty" look the same
 * to them. The person themself also gets their privacy settings.
 */
export function profileView(u: ProfileRow, r: Relation) {
  const p = privacyOf(u);
  const show = (f: PrivacyField) => allows(p[f], r);
  const view: Record<string, unknown> = {
    id: u.id,
    username: u.username,
    displayName: u.display_name,
    avatarColor: u.avatar_color,
    homeServer: u.home_server_id ?? null,
  };
  const status = activeStatus(u);
  if (show("bio") && status.text) view.statusText = status.text;
  if (show("bio") && status.emoji) view.statusEmoji = status.emoji;
  if (show("bio") && (status.text || status.emoji) && status.expiresAt) view.statusExpiresAt = status.expiresAt;
  if (show("name")) {
    if (u.first_name) view.firstName = u.first_name;
    if (u.last_name) view.lastName = u.last_name;
  }
  if (show("photo") && u.photo_rev) view.photoRev = u.photo_rev;
  if (show("birthday") && u.birthday) view.birthday = u.birthday;
  if (show("phone") && u.phone) view.phone = u.phone;
  if (r.self) {
    view.privacy = p;
    view.notificationsPausedUntil = notificationsPaused(u) ? u.notifications_paused_until : null;
  }
  return view;
}

/** Whether the viewer may see the person's online state and last seen time. */
export function canSeeLastSeen(ctx: AppContext, viewerId: string, u: ProfileRow): boolean {
  return allows(privacyOf(u).lastSeen, relation(ctx, viewerId, u.id));
}

/** Birthday: a real calendar date, with or without the year; years from 1900 to this year. */
export function validBirthday(s: string): boolean {
  const m = /^(?:(\d{4})|-)-(\d{2})-(\d{2})$/.exec(s);
  if (!m) return false;
  const year = m[1] ? Number(m[1]) : 2000; // a leap year, so --02-29 is allowed
  const month = Number(m[2]);
  const day = Number(m[3]);
  if (m[1] && (year < 1900 || year > new Date().getUTCFullYear())) return false;
  const d = new Date(Date.UTC(year, month - 1, day));
  return d.getUTCFullYear() === year && d.getUTCMonth() === month - 1 && d.getUTCDate() === day;
}

/** Phone numbers in E.164 form: "+" and 7 to 15 digits, no leading zero after the "+". */
export const PHONE = /^\+[1-9]\d{6,14}$/;

/** JPEG, the only profile photo format (re-encoded on the phone, so metadata is already gone). */
export function isJpeg(b: Buffer): boolean {
  return b.length > 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff;
}

/**
 * A person as listed to someone who shares a conversation with them: what the list shows everyone, plus the photo and
 * bio their owner shows contacts, and their online state only if their "last seen" setting allows it.
 */
export function memberView(ctx: AppContext, viewerId: string, u: ProfileRow, base: Record<string, unknown>) {
  const r = relation(ctx, viewerId, u.id);
  const p = privacyOf(u);
  const status = activeStatus(u);
  const seen = allows(p.lastSeen, r);
  return {
    ...base,
    statusText: allows(p.bio, r) ? status.text : null,
    statusEmoji: allows(p.bio, r) ? status.emoji : null,
    ...(allows(p.photo, r) && u.photo_rev ? { photoRev: u.photo_rev } : {}),
    online: seen ? ctx.hub.isUserOnline(u.id) : null,
    // "Ada has paused notifications": shown to the people who may see when Ada was last online (round 3).
    notificationsPaused: seen ? notificationsPaused(u) : null,
  };
}
