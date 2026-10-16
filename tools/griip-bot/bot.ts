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
 * GRIIP_BOT_SCREEN=1 also publishes a landscape "screen share" pattern from 10 s to 40 s into a video call,
 * so the peer's switch from camera to screen share and back can be checked.
 * Stress test for the frame-cryptor observer abort (docs/KNOWN_ISSUES.md › Calls):
 *   GRIIP_BOT_KEY_FLAP=1     switch the key index the bot encrypts with between 0 and a missing one every
 *                            GRIIP_BOT_KEY_FLAP_MS (default 40) ms, so the peer's decryptors keep reporting
 *                            OK ↔ MISSING_KEY. The peer shows an E2EE problem while this runs.
 *   GRIIP_BOT_EXTRA_AUDIO=n  publish n extra tone tracks, so the peer has n more decryptors reporting.
 *   GRIIP_BOT_CHURN_MS=n     every n ms unpublish and republish those tone tracks (remote unpublish on the peer).
 *   GRIIP_BOT_END_FIRST=1    at the deadline, end the call through the API and keep sending media for 5 s,
 *                            so the peer tears its room down while frames are still arriving.
 */
import {
  AudioFrame, AudioSource, AudioStream, dispose, LocalAudioTrack, LocalVideoTrack, Room, RoomEvent, TrackKind,
  TrackPublishOptions, TrackSource, VideoBufferType, VideoFrame, VideoSource, VideoStream,
} from "@livekit/rtc-node";
import { E2eeRequest, FrameCryptorSetKeyIndexRequest } from "@livekit/rtc-ffi-bindings";
import crypto from "node:crypto";
import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { canonical, ed25519FromSeed, GriipClient, sign } from "../../server/test/support/griipClient.ts";

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
  const linkFrom = process.env.GRIIP_BOT_LINK_FROM;
  let c: GriipClient;
  if (linkFrom) {
    // Add a second *device* to an existing bot account (same identity key, new device keys).
    const p = JSON.parse(fs.readFileSync(path.join(here, linkFrom), "utf8"));
    c = new GriipClient(SERVER, p.username, p.displayName);
    c.identity = ed25519FromSeed(Buffer.from(p.identitySeed, "base64"));
    const { nonce } = await c.req("POST", "/v1/auth/challenge");
    const r = await c.req("POST", "/v1/auth/add-device", {
      username: p.username,
      device: { name: "griip-bot-device-2", signingKey: c.signingKeyB64, encryptionKey: c.encKeyB64, certificate: c.certificate() },
      nonce,
      signature: sign(c.device, canonical.authProof("add-device", nonce, c.signingKeyB64)),
    });
    c.userId = r.userId;
    c.deviceId = r.deviceId;
    c.token = r.sessionToken;
  } else {
    c = new GriipClient(SERVER, `griipbot${crypto.randomBytes(2).toString("hex")}`, "Griip Bot");
    await c.register();
  }
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
  if (process.env.GRIIP_BOT_QUIET === "1") log("quiet mode: no greeting");
  else {
  const greeting = await bot.send(dm, { type: "text", text: `👋 Hi ${peer.displayName}! Griip Bot is online. I echo messages and auto-answer voice/video calls with a test pattern + tone — call me to test calling and screen sharing.` });
  log(`sent greeting ${greeting.messageId} (relay accepted at ${greeting.serverTs})`);
  }
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
  // GRIIP_BOT_EXTRA_AUDIO=n publishes n more tone tracks: more remote decryptors on the peer for the key-flap stress test.
  const extra = Array.from({ length: Number(process.env.GRIIP_BOT_EXTRA_AUDIO ?? 0) }, () => new AudioSource(48000, 1));
  const publishTone = (i: number) => room.localParticipant!.publishTrack(LocalAudioTrack.createAudioTrack(`bot-tone-${i}`, extra[i]), new TrackPublishOptions({ source: TrackSource.SOURCE_UNKNOWN }));
  const toneSids: (string | undefined)[] = [];
  extra.forEach((_, i) => pubs.push(publishTone(i).then((p) => { toneSids[i] = p.sid; })));
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
    for (const src of extra) void src.captureFrame(f);
    t++;
  }, 100);
  const screen = video && process.env.GRIIP_BOT_SCREEN === "1" ? startScreenShare(room) : null;
  // GRIIP_BOT_CHURN_MS=n: every n ms unpublish and republish each extra tone track (remote-unpublish stress on the peer).
  const churnMs = Number(process.env.GRIIP_BOT_CHURN_MS ?? 0);
  let churning = churnMs > 0 && extra.length > 0;
  if (churning) (async () => {
    await Promise.all(pubs);
    let cycles = 0;
    while (churning) {
      await new Promise((r) => setTimeout(r, churnMs));
      for (let i = 0; i < extra.length && churning; i++) {
        const sid = toneSids[i];
        if (sid) await room.localParticipant!.unpublishTrack(sid).catch(() => {});
        toneSids[i] = (await publishTone(i).catch(() => undefined))?.sid;
      }
      if (++cycles % 5 === 0) log(`🔁 republished ${extra.length} tone tracks ${cycles} times`);
    }
  })().catch((e) => log("churn stopped:", e));
  return { stop: () => { churning = false; clearInterval(timer); screen?.stop(); }, published: Promise.all(pubs) };
}

/** Landscape 960×540 pattern published as SOURCE_SCREENSHARE between 10 s and 40 s. */
function startScreenShare(room: Room) {
  const w = 960, h = 540, src = new VideoSource(w, h);
  let t = 0, sid: string | undefined;
  const timer = setInterval(() => {
    const px = new Uint8Array(w * h * 4);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4, bar = y > 60 && y < 500 && x > 40 && x < 920 && ((y >> 4) & 1) && x < 120 + ((y * 37 + t * 8) % 760);
      px[i] = bar ? 230 : 24; px[i + 1] = bar ? 230 : 32; px[i + 2] = bar ? 240 : 48; px[i + 3] = 255;
      if (y < 40) { px[i] = 31; px[i + 1] = 181; px[i + 2] = 166; }
    }
    src.captureFrame(new VideoFrame(px, w, h, VideoBufferType.RGBA));
    t++;
  }, 200);
  const on = setTimeout(async () => {
    const pub = await room.localParticipant!.publishTrack(LocalVideoTrack.createVideoTrack("bot-screen", src), new TrackPublishOptions({ source: TrackSource.SOURCE_SCREENSHARE }));
    sid = pub.sid;
    log("🖥  publishing screen share 960x540");
  }, 10_000);
  const off = setTimeout(async () => {
    if (sid) await room.localParticipant!.unpublishTrack(sid).catch(() => {});
    log("🖥  stopped screen share");
  }, 40_000);
  return { stop: () => { clearInterval(timer); clearTimeout(on); clearTimeout(off); } };
}

/**
 * GRIIP_BOT_KEY_FLAP: alternate the key index the bot encrypts with between 0 (the call key) and 1 (a key the
 * peer doesn't have). The peer reports MISSING_KEY ↔ OK on every switch. Flipping the key *value* instead is
 * silent there: Android's default failureTolerance (-1) never reports decryption failures.
 * rtc-node 1.0.0's FrameCryptor.setKeyIndex omits the required track sid, so the FFI request is sent directly.
 */
function startKeyFlap(room: Room) {
  const e2ee = room.e2eeManager as any;
  const me = room.localParticipant?.identity;
  if (!e2ee?.keyProvider || !me) { log("key flap: room has no E2EE manager"); return null; }
  const { FfiClient } = createRequire(import.meta.url)(path.join(here, "node_modules/@livekit/rtc-node/dist/ffi_client.cjs"));
  const e2eeReq = (message: any) => FfiClient.instance.request({ message: { case: "e2ee", value: new E2eeRequest({ roomHandle: e2ee.roomHandle, message }) } });
  e2ee.keyProvider.setSharedKey(new TextEncoder().encode(crypto.randomBytes(32).toString("base64")), 1);
  const setIndex = (keyIndex: number) => {
    const res: any = e2eeReq({ case: "managerGetFrameCryptors", value: {} });
    for (const c of res.message.value.frameCryptors) {
      if (c.participantIdentity !== me) continue;
      e2eeReq({ case: "cryptorSetKeyIndex", value: new FrameCryptorSetKeyIndexRequest({ participantIdentity: me, trackSid: c.trackSid, keyIndex }) });
    }
  };
  const ms = Number(process.env.GRIIP_BOT_KEY_FLAP_MS ?? 40);
  let n = 0;
  const timer = setInterval(() => setIndex(++n % 2), ms);
  log(`⚠️  STRESS TEST: switching the media key index 0 ↔ 1 every ${ms} ms (the peer will report a missing key)`);
  return { stop: () => { clearInterval(timer); setIndex(0); } };
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
  if (key && process.env.GRIIP_BOT_WRONG_KEY === "1") {
    key = crypto.randomBytes(32).toString("base64");
    log("⚠️  NEGATIVE TEST: joining with a deliberately WRONG media key");
  }
  // Android's BaseKeyProvider.setSharedKey(String) uses the string's UTF-8 bytes; mirror that.
  if (key) opts.encryption = { keyProviderOptions: { sharedKey: new TextEncoder().encode(key) } };
  await room.connect(media.url, media.token, opts);
  log(`🎥 connected to ${media.url} as ${room.localParticipant?.identity} (E2EE ${key ? "on" : "OFF"})`);
  await bot.req("POST", `/v1/calls/${callId}/connected`).catch(() => {});
  const m = startMedia(room, video);
  await m.published;
  log("📡 publishing test pattern" + (video ? " + tone" : " (tone only)"));
  const flap = key && process.env.GRIIP_BOT_KEY_FLAP === "1" ? startKeyFlap(room) : null;
  const deadline = Date.now() + seconds * 1000;
  let ended = false;
  while (Date.now() < deadline && !ended) {
    if (bot.events.some((e) => e.t === "call" && e.event === "ended" && e.call.id === callId)) ended = true;
    await new Promise((r) => setTimeout(r, 500));
  }
  let endedFirst = false;
  if (!ended && process.env.GRIIP_BOT_END_FIRST === "1") {
    await bot.req("POST", `/v1/calls/${callId}/end`).catch(() => {});
    log("📴 ended the call through the API; still in the room sending media for 5 s");
    await new Promise((r) => setTimeout(r, 5000));
    endedFirst = true;
  }
  flap?.stop();
  m.stop();
  watch.stop();
  log("final:", JSON.stringify(watch.counts), "encryption:", JSON.stringify(watch.enc), ended ? "(peer ended the call)" : endedFirst ? "(bot ended the call first)" : "(timeout)");
  await room.disconnect();
  if (!ended && !endedFirst) await bot.req("POST", `/v1/calls/${callId}/end`).catch(() => {});
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
  // Report the call's own state changes: "alerted" (a callee device is ringing), "declined", "ended" with its reason.
  let seenEvents = 0;
  const watch = setInterval(() => {
    const evs = bot.events.filter((e) => e.t === "call" && e.call?.id === res.call.id);
    for (const e of evs.slice(seenEvents)) log(`📣 call event: ${e.event}${e.call?.endReason ? ` (reason ${e.call.endReason})` : ""}${e.call?.alertedAt ? " alertedAt set" : ""}`);
    seenEvents = evs.length;
  }, 250);
  try {
    await runMedia(bot, res.call.id, res.media, key, video, seconds);
  } finally {
    clearInterval(watch);
  }
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
