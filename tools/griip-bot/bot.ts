/**
 * Griip Bot — a headless Griip Message peer for testing the Android app on real devices.
 *
 *   npm run bot -- chat   <peerUsername> [seconds]          DM the peer, echo every message back
 *   npm run bot -- call   <peerUsername> [voice|video] [s]  ring the peer, exchange E2EE media
 *   npm run bot -- answer [seconds]                          wait for the peer to call, answer
 *
 * Env: GRIIP_SERVER (default https://msg.griipbuddy.cloud). The bot's identity is stored in
 * bot-identity.json next to this file (private keys — never commit).
 * Media: publishes a synthetic moving test pattern + 440 Hz tone, and logs every second how many
 * decoded frames it receives per remote track (camera / microphone / screen share).
 */
import {
  AudioFrame, AudioSource, AudioStream, dispose, LocalAudioTrack, LocalVideoTrack, Room, RoomEvent, TrackKind,
  TrackPublishOptions, TrackSource, VideoBufferType, VideoFrame, VideoSource, VideoStream,
} from "@livekit/rtc-node";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ed25519FromSeed, GriipClient } from "../../server/test/support/griipClient.ts";

const here = path.dirname(fileURLToPath(import.meta.url));
const SERVER = process.env.GRIIP_SERVER ?? "https://msg.griipbuddy.cloud";
const STORE = path.join(here, process.env.GRIIP_BOT_ID ?? "bot-identity.json");
const log = (...a: unknown[]) => console.log(new Date().toISOString().slice(11, 19), ...a);
/** Never print key material (call media keys, attachment keys) into logs. */
const redact = (p: any) => JSON.stringify(p, (k, v) => (k === "e2eeKey" || k === "key" || k === "nonce" ? "[redacted]" : v));

async function loadBot(): Promise<GriipClient> {
  if (fs.existsSync(STORE)) {
    const s = JSON.parse(fs.readFileSync(STORE, "utf8"));
    const c = new GriipClient(SERVER, s.username, s.displayName);
    c.identity = ed25519FromSeed(Buffer.from(s.identitySeed, "base64"));
    c.device = ed25519FromSeed(Buffer.from(s.deviceSeed, "base64"));
    c.enc = { priv: Buffer.from(s.encPriv, "base64"), pub: Buffer.from(s.encPub, "base64") };
    c.userId = s.userId;
    c.deviceId = s.deviceId;
    await c.login();
    return c;
  }
  const c = new GriipClient(SERVER, `griipbot${crypto.randomBytes(2).toString("hex")}`, "Griip Bot");
  await c.register();
  fs.writeFileSync(
    STORE,
    JSON.stringify({
      username: c.username, displayName: c.displayName, userId: c.userId, deviceId: c.deviceId,
      identitySeed: c.identity.seed.toString("base64"), deviceSeed: c.device.seed.toString("base64"),
      encPriv: c.enc.priv.toString("base64"), encPub: c.enc.pub.toString("base64"),
    }, null, 2),
    { mode: 0o600 },
  );
  return c;
}

async function findPeer(bot: GriipClient, username: string) {
  const r = await bot.req("GET", `/v1/users/search?q=${encodeURIComponent(username)}`);
  const u = r.users.find((x: any) => x.username === username.toLowerCase());
  if (!u) throw new Error(`no user @${username}`);
  return u;
}

async function chat(peerName: string, seconds: number) {
  const bot = await loadBot();
  const peer = await findPeer(bot, peerName);
  const dm = (await bot.req("POST", "/v1/conversations/dm", { userId: peer.id })).conversation.id;
  await bot.connect();
  log(`bot @${bot.username} connected; DM with @${peer.username} (${dm})`);
  const greeting = await bot.send(dm, { type: "text", text: `👋 Hi ${peer.displayName}! Griip Bot is online. I echo messages and auto-answer voice/video calls with a test pattern + tone — call me to test calling and screen sharing.` });
  log(`sent greeting ${greeting.messageId} (relay accepted at ${greeting.serverTs})`);
  const seen = new Set<string>();
  const answered = new Set<string>();
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    for (const m of bot.received) {
      if (seen.has(m.messageId) || m.conversationId !== dm) continue;
      seen.add(m.messageId);
      const p = m.payload;
      log(`⇐ [${p.type}] from ${m.senderUserId === peer.id ? "@" + peer.username : m.senderUserId} dev=${m.senderDeviceId} sentAt=${m.sentAt}`, redact(p));
      if (p.type === "text" && p.attachments?.length) {
        for (const a of p.attachments) {
          try {
            const ct: Buffer = await bot.req("GET", `/v1/attachments/${a.id}`);
            const okHash = crypto.createHash("sha256").update(ct).digest("base64") === a.sha256;
            const body = ct.subarray(0, ct.length - 16);
            const d = crypto.createDecipheriv("aes-256-gcm", Buffer.from(a.key, "base64"), Buffer.from(a.nonce, "base64"));
            d.setAuthTag(ct.subarray(ct.length - 16));
            const plain = Buffer.concat([d.update(body), d.final()]);
            const magic = plain.subarray(0, 4).toString("hex");
            log(`📎 ${a.name}: ${ct.length} B ciphertext, hash ${okHash ? "ok" : "MISMATCH"}, decrypted ${plain.length} B (magic ${magic})`);
            await bot.send(dm, { type: "text", text: `📎 Got "${a.name}" — ${plain.length} bytes, integrity ${okHash ? "verified" : "FAILED"}, decrypted on my side (${a.mime}).`, replyTo: m.messageId });
          } catch (e) {
            log("✗ attachment failed:", String(e));
          }
        }
        continue;
      }
      if (p.type === "text") {
        const about = /who are you|who you are|what are you/i.test(p.text ?? "")
          ? "I'm Griip Bot, an automated test participant run by the Griip Message dev setup. I echo your messages and auto-answer calls with a test pattern + 440 Hz tone so we can verify end-to-end encryption, delivery and media. Call me (voice or video) to test calling or screen sharing."
          : `Echo: ${p.text ?? ""}`;
        const reply = await bot.send(dm, { type: "text", text: about.slice(0, 2000), replyTo: m.messageId });
        log(`⇒ echo ${reply.messageId}`);
        await bot.send(dm, { type: "reaction", target: m.messageId, emoji: "👍", add: true }, "control");
        await bot.send(dm, { type: "read", upTo: Date.now() }, "control");
      }
    }
    for (const r of bot.receipts.splice(0)) log(`✓ receipt ${r.payload.status} for ${r.messageId} from ${r.senderUserId}`);
    const ring = bot.events.find((e) => e.t === "call" && e.event === "ring" && !answered.has(e.call.id));
    if (ring) {
      answered.add(ring.call.id);
      log(`📞 incoming ${ring.call.video ? "video" : "voice"} call ${ring.call.id} — auto-answering`);
      void (async () => {
        try {
          const keyMsg = await bot.waitFor(() => bot.received.find((x) => x.payload.type === "call_invite" && x.payload.callId === ring.call.id), 15000);
          const join = await bot.req("POST", `/v1/calls/${ring.call.id}/join`);
          await runMedia(bot, ring.call.id, join.media, keyMsg.payload.e2eeKey, ring.call.video, 600);
        } catch (e) {
          log("✗ answer failed:", String(e));
        }
      })();
    }
    for (const e of bot.events) {
      if (e._seen) continue;
      e._seen = true;
      if (e.t === "typing" && e.on) log(`… typing from ${e.u}`);
      if (e.t === "decrypt_error") log("✗ decrypt error", e.error);
      if (e.t === "call" && e.event !== "ring") log(`☎ call ${e.event}: ${e.call.id} state=${e.call.state}${e.call.endReason ? " reason=" + e.call.endReason : ""}`);
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  await bot.disconnect();
  log("chat session finished");
}

function startMedia(room: Room, video: boolean) {
  const vsrc = new VideoSource(640, 360);
  const asrc = new AudioSource(48000, 1);
  const pubs: Promise<unknown>[] = [room.localParticipant!.publishTrack(LocalAudioTrack.createAudioTrack("bot-mic", asrc), new TrackPublishOptions({ source: TrackSource.SOURCE_MICROPHONE }))];
  if (video) pubs.push(room.localParticipant!.publishTrack(LocalVideoTrack.createVideoTrack("bot-cam", vsrc), new TrackPublishOptions({ source: TrackSource.SOURCE_CAMERA })));
  let t = 0;
  const timer = setInterval(() => {
    if (video) {
      const w = 640, h = 360, px = new Uint8Array(w * h * 4);
      for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const i = (y * w + x) * 4, band = ((x + t * 6) >> 5) & 1;
        px[i] = band ? 31 : 52; px[i + 1] = band ? 181 : 200; px[i + 2] = band ? 166 : 194; px[i + 3] = 255;
        if (Math.abs(y - h / 2 - Math.sin((x + t * 4) / 40) * 60) < 6) { px[i] = 255; px[i + 1] = 255; px[i + 2] = 255; }
      }
      vsrc.captureFrame(new VideoFrame(px, w, h, VideoBufferType.RGBA));
    }
    const f = AudioFrame.create(48000, 1, 4800);
    for (let i = 0; i < f.data.length; i++) f.data[i] = Math.round(Math.sin(((t * 4800 + i) * 2 * Math.PI * 440) / 48000) * 6000);
    void asrc.captureFrame(f);
    t++;
  }, 100);
  return { stop: () => clearInterval(timer), published: Promise.all(pubs) };
}

function watchRemote(room: Room) {
  const counts: Record<string, { frames: number; audible: number; w?: number; h?: number }> = {};
  const enc: Record<string, boolean> = {};
  room.on(RoomEvent.ParticipantEncryptionStatusChanged, (isEnc: boolean, p: any) => { enc[p.identity] = isEnc; log(`🔐 encryption status ${p.identity}: ${isEnc}`); });
  room.on(RoomEvent.ParticipantConnected, (p: any) => log(`➕ participant joined: ${p.identity} (${p.name})`));
  room.on(RoomEvent.ParticipantDisconnected, (p: any) => log(`➖ participant left: ${p.identity}`));
  room.on(RoomEvent.TrackSubscribed, (track: any, pub: any, p: any) => {
    const key = `${p.identity}/${TrackSource[pub.source] ?? pub.source}`;
    counts[key] = { frames: 0, audible: 0 };
    log(`📥 subscribed ${key} (${track.kind === TrackKind.KIND_VIDEO ? "video" : "audio"})`);
    if (track.kind === TrackKind.KIND_VIDEO) (async () => { for await (const ev of new VideoStream(track)) { counts[key].frames++; counts[key].w = ev.frame.width; counts[key].h = ev.frame.height; } })().catch(() => {});
    else (async () => { for await (const ev of new AudioStream(track, 48000, 1)) { counts[key].frames++; if (ev.data.some((s: number) => Math.abs(s) > 300)) counts[key].audible++; } })().catch(() => {});
  });
  room.on(RoomEvent.TrackUnsubscribed, (_t: any, pub: any, p: any) => log(`📤 unsubscribed ${p.identity}/${TrackSource[pub.source] ?? pub.source}`));
  const timer = setInterval(() => {
    const parts = Object.entries(counts).map(([k, v]) => `${k}: ${v.frames} frames${v.w ? ` ${v.w}x${v.h}` : ""}${k.includes("MICROPHONE") ? `, ${v.audible} audible` : ""}`);
    if (parts.length) log("📊", parts.join(" | "));
  }, 10000);
  return { counts, enc, stop: () => clearInterval(timer) };
}

async function runMedia(bot: GriipClient, callId: string, media: { url: string; token: string }, key: string | null, video: boolean, seconds: number) {
  const room = new Room();
  const watch = watchRemote(room);
  const opts: any = { autoSubscribe: true, dynacast: false };
  // Android's BaseKeyProvider.setSharedKey(String) uses the string's UTF-8 bytes; mirror that.
  if (key) opts.encryption = { keyProviderOptions: { sharedKey: new TextEncoder().encode(key) } };
  await room.connect(media.url, media.token, opts);
  log(`🎥 connected to ${media.url} as ${room.localParticipant?.identity} (E2EE ${key ? "on" : "OFF"})`);
  await bot.req("POST", `/v1/calls/${callId}/connected`).catch(() => {});
  const m = startMedia(room, video);
  await m.published;
  log("📡 publishing test pattern" + (video ? " + tone" : " (tone only)"));
  const deadline = Date.now() + seconds * 1000;
  let ended = false;
  while (Date.now() < deadline && !ended) {
    if (bot.events.some((e) => e.t === "call" && e.event === "ended" && e.call.id === callId)) ended = true;
    await new Promise((r) => setTimeout(r, 500));
  }
  m.stop();
  watch.stop();
  log("final:", JSON.stringify(watch.counts), "encryption:", JSON.stringify(watch.enc), ended ? "(peer ended the call)" : "(timeout)");
  await room.disconnect();
  if (!ended) await bot.req("POST", `/v1/calls/${callId}/end`).catch(() => {});
}

async function call(peerName: string, mode: string, seconds: number) {
  const bot = await loadBot();
  const peer = await findPeer(bot, peerName);
  const dm = (await bot.req("POST", "/v1/conversations/dm", { userId: peer.id })).conversation.id;
  await bot.connect();
  const video = mode !== "voice";
  const res = await bot.req("POST", "/v1/calls", { conversationId: dm, video });
  const key = crypto.randomBytes(32).toString("base64");
  await bot.send(dm, { type: "call_invite", callId: res.call.id, video, e2eeKey: key }, "control");
  log(`📞 ringing @${peer.username} (${video ? "video" : "voice"} call ${res.call.id}); key sent via E2EE message`);
  await runMedia(bot, res.call.id, res.media, key, video, seconds);
  await bot.disconnect();
}

async function answer(seconds: number) {
  const bot = await loadBot();
  await bot.connect();
  log(`bot @${bot.username} waiting for an incoming call…`);
  const ring = await bot.waitFor(() => bot.events.find((e) => e.t === "call" && e.event === "ring"), seconds * 1000);
  log(`📞 incoming ${ring.call.video ? "video" : "voice"} call ${ring.call.id} from ${ring.from?.displayName}`);
  const keyMsg = await bot.waitFor(() => bot.received.find((m) => m.payload.type === "call_invite" && m.payload.callId === ring.call.id), 15000);
  log("🔑 got media key via E2EE control message");
  const join = await bot.req("POST", `/v1/calls/${ring.call.id}/join`);
  await runMedia(bot, ring.call.id, join.media, keyMsg.payload.e2eeKey, ring.call.video, seconds);
  await bot.disconnect();
}

const [cmd, a1, a2, a3] = process.argv.slice(2);
try {
  if (cmd === "chat") await chat(a1, Number(a2 ?? 300));
  else if (cmd === "call") await call(a1, a2 ?? "video", Number(a3 ?? 60));
  else if (cmd === "answer") await answer(Number(a1 ?? 120));
  else console.log("usage: bot chat <user> [s] | call <user> [voice|video] [s] | answer [s]");
} finally {
  await dispose().catch(() => {});
}
process.exit(0);
