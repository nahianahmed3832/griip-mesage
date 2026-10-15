import { AccessToken, RoomServiceClient, WebhookReceiver, type WebhookEvent } from "livekit-server-sdk";
import type { Config } from "./config.js";

/**
 * Thin wrapper around the official LiveKit server SDK. Tokens are short-lived (10 min) and
 * scoped to exactly one room. LiveKit refreshes tokens for already-connected participants.
 */
export class LiveKitService {
  private rooms: RoomServiceClient;
  private receiver: WebhookReceiver;

  constructor(private cfg: NonNullable<Config["livekit"]>) {
    this.rooms = new RoomServiceClient(cfg.apiUrl, cfg.apiKey, cfg.apiSecret);
    this.receiver = new WebhookReceiver(cfg.apiKey, cfg.apiSecret);
  }

  get publicUrl(): string {
    return this.cfg.publicUrl;
  }

  async token(p: { room: string; identity: string; name: string; metadata: string }): Promise<string> {
    const at = new AccessToken(this.cfg.apiKey, this.cfg.apiSecret, {
      identity: p.identity,
      name: p.name,
      metadata: p.metadata,
      ttl: "10m",
    });
    at.addGrant({
      room: p.room,
      roomJoin: true,
      canPublish: true,
      canSubscribe: true,
      canPublishData: true,
      canUpdateOwnMetadata: false,
    });
    return at.toJwt();
  }

  async deleteRoom(room: string): Promise<void> {
    try {
      await this.rooms.deleteRoom(room);
    } catch {
      // Room may already be gone (empty-timeout) — that is fine.
    }
  }

  /** Distinct Griip user ids currently connected to a room, or null if LiveKit is unreachable. */
  async participantUsers(room: string): Promise<Set<string> | null> {
    try {
      const list = await this.rooms.listParticipants(room);
      return new Set(list.map((p) => p.identity.split(".")[0]));
    } catch (e) {
      // A room that was never created / already closed reports "not found": that's empty.
      const err = e as { status?: number; code?: string; message?: string };
      const msg = String(err?.message ?? e).toLowerCase();
      if (err?.status === 404 || err?.code === "not_found" || msg.includes("not found") || msg.includes("does not exist")) return new Set();
      return null;
    }
  }

  async removeParticipant(room: string, identity: string): Promise<void> {
    try {
      await this.rooms.removeParticipant(room, identity);
    } catch {
      /* not in room */
    }
  }

  async receiveWebhook(body: string, auth: string | undefined): Promise<WebhookEvent> {
    return this.receiver.receive(body, auth);
  }
}
