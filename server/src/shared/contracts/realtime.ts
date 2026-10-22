/**
 * The WebSocket contract (`/v1/ws`): every frame the server sends and every frame it accepts, as one discriminated
 * union on `t` (docs/architecture/ARCHITECTURE_1_0_PLAN.md §4). `Hub` only sends `ServerEvent`s, so a new frame or
 * field has to be added here first, where every client team can see it.
 *
 * Compatibility: fields are only ever added; clients ignore frames and fields they don't know.
 * Ids are opaque strings; times are epoch milliseconds.
 */

// ───────────── Shared payloads ─────────────

/** One delivery-queue item: an encrypted envelope for this device, or a delivery receipt. */
export interface QueueItemWire {
  /** Per-device order: items arrive in increasing id order and are acknowledged by id. */
  id: number;
  kind: "envelope" | "receipt";
  conversationId: string;
  messageId: string;
  senderUserId: string;
  senderDeviceId: string;
  createdAt: number;
  expiresAt: number;
  /** The envelope or receipt as the sender submitted it (ciphertext; the server can't read it). */
  payload: unknown;
}

export type CallState = "RINGING" | "ACTIVE" | "ENDED";

export interface CallParticipantWire {
  userId: string;
  deviceId: string;
  joinedAt: number;
}

/** Who took part in an ended call, merged over rejoins (the call history line). */
export interface CallAttendanceWire {
  userId: string;
  joinedAt: number;
  leftAt: number;
  seconds: number;
}

export interface CallWire {
  id: string;
  conversationId: string;
  conversationType: string | null;
  createdBy: string;
  video: boolean;
  state: CallState;
  createdAt: number;
  answeredAt: number | null;
  alertedAt: number | null;
  endedAt: number | null;
  endReason: string | null;
  participants: CallParticipantWire[];
  voiceRoom: boolean;
  /** Voice rooms: people with a raised hand, in the order they raised it. */
  hands: string[];
  /** Ended calls only. */
  attended?: CallAttendanceWire[];
}

export type PresenceState = "online" | "idle" | "dnd" | "offline";

// ───────────── Server → client ─────────────

export type CallEventName = "ring" | "started" | "updated" | "ended" | "declined" | "dismissed" | "alerted";

/** Extra fields some call events carry. */
export interface CallEventExtras {
  /** `ring`: who is calling. */
  from?: { userId: string; displayName: string };
  /** `declined`: who declined, and why. */
  userId?: string;
  reason?: "declined" | "busy";
}

export type CallEvent =
  | ({ t: "call"; event: CallEventName; call: CallWire } & CallEventExtras)
  /** Voice rooms: a moderator muted or removed this person. */
  | { t: "call"; event: "moderated"; action: "mute" | "lower_hand" | "remove"; call: CallWire };

export type ServerEvent =
  | { t: "hello"; userId: string; deviceId: string; serverTime: number }
  | { t: "pong"; serverTime: number }
  /** Queue items: live (`backlog` absent) or a backlog batch after connecting (`backlog: true`, acknowledged with it). */
  | { t: "queue"; items: QueueItemWire[]; more: boolean; backlog?: true }
  /** The backlog has been sent completely. */
  | { t: "queue.drained" }
  | { t: "typing"; c: string; u: string; on: boolean }
  | { t: "presence"; userId: string; online: boolean; presence: PresenceState; lastSeenAt: number | null }
  | CallEvent
  /** Reload the conversation (membership, settings, or it's gone). */
  | { t: "conv.updated"; conversationId: string }
  /** Reload the community. */
  | { t: "org.updated"; orgId: string }
  | { t: "org.deleted"; orgId: string }
  /** Moderators: the number of open reports changed. */
  | { t: "org.reports"; orgId: string; open: number }
  /** Approvers: join applications changed. */
  | { t: "org.applications"; orgId: string }
  /** The applicant: their application was decided. */
  | { t: "org.application"; orgId: string; status: "approved" | "denied" }
  /** Safety moderators: an activity alert (a burst of joins). */
  | { t: "org.alert"; orgId: string; kind: "joins"; count: number; at: number }
  /** Someone's devices changed: refresh their keys before encrypting to them. */
  | { t: "devices.changed"; userId: string }
  | { t: "connections.updated" }
  | { t: "prefs.updated"; workspaceId: string; rev: number }
  | { t: "reminders.changed"; id: string; rev?: number; deleted?: true }
  | { t: "reminder.due"; id: string; occurrenceAt: number; firedAt: number }
  | { t: "meeting.updated" | "meeting.soon"; orgId: string; meetingId: string; startsAt: number };

export type ServerEventName = ServerEvent["t"];

// ───────────── Client → server ─────────────

export type ClientEvent =
  /** Browsers only, as the first frame (they can't set headers on a WebSocket). */
  | { t: "auth"; token: string }
  | { t: "ping" }
  /** Acknowledge queue items (at most 500 ids per frame); `backlog: true` asks for the next backlog batch. */
  | { t: "ack"; ids: number[]; backlog?: boolean }
  | { t: "typing"; c: string; on: boolean };

export type ClientEventName = ClientEvent["t"];
