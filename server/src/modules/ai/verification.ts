import type { Capability } from "./capabilities.js";
import type { Invocation, InvocationResult } from "./gateway.js";
import type { ProviderError } from "./providers.js";

/**
 * A capability's verification request and what its answer must prove (docs/AI_WORKFORCE.md §4). HTTP 200 proves
 * nothing on its own: the answer has to be the kind of output Meldiq consumes (text that describes the test image,
 * real MP3 or image bytes, one finite vector per input). Inputs are tiny so a verification costs next to nothing.
 */

/**
 * Room for the answer. Reasoning models spend part of max_completion_tokens thinking before they write anything, so a
 * budget of a few tokens comes back empty even from a model that works.
 */
const VERIFY_OUTPUT_TOKENS = 512;

/** A 1×1 red PNG. */
const RED_PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";
const RED_WORDS = /\b(red|crimson|scarlet)\b/i;

/** Half a second of 16 kHz mono silence as WAV. */
function silentWav(): Buffer {
  const samples = 8000;
  const b = Buffer.alloc(44 + samples * 2);
  b.write("RIFF", 0, "latin1");
  b.writeUInt32LE(36 + samples * 2, 4);
  b.write("WAVEfmt ", 8, "latin1");
  b.writeUInt32LE(16, 16);
  b.writeUInt16LE(1, 20);
  b.writeUInt16LE(1, 22);
  b.writeUInt32LE(16000, 24);
  b.writeUInt32LE(32000, 28);
  b.writeUInt16LE(2, 32);
  b.writeUInt16LE(16, 34);
  b.write("data", 36, "latin1");
  b.writeUInt32LE(samples * 2, 40);
  return b;
}

const EMBED_INPUTS = ["pong"];

export const VERIFY_REQUESTS: Record<Capability, Invocation> = {
  text: { capability: "text", category: "fast", chat: { messages: [{ role: "user", text: "Reply with the single word: pong" }], maxOutputTokens: VERIFY_OUTPUT_TOKENS } },
  vision: {
    capability: "vision",
    category: "vision",
    chat: { messages: [{ role: "user", text: "What colour is this image? One word.", images: [{ mime: "image/png", base64: RED_PIXEL }] }], maxOutputTokens: VERIFY_OUTPUT_TOKENS },
  },
  transcription: { capability: "transcription", category: "transcription", audio: { bytes: silentWav(), mime: "audio/wav", name: "silence.wav", seconds: 0.5 } },
  speech: { capability: "speech", category: "speech", text: "OK." },
  image_generation: { capability: "image_generation", category: "image_generation", prompt: "A plain red square." },
  embeddings: { capability: "embeddings", category: "embeddings", texts: EMBED_INPUTS },
};

/** The image format [bytes] really are, from their signature; null when they aren't a format agents can use. */
export function sniffImage(bytes: Buffer): "image/png" | "image/jpeg" | "image/webp" | "image/gif" | null {
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return "image/png";
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.length >= 12 && bytes.toString("latin1", 0, 4) === "RIFF" && bytes.toString("latin1", 8, 12) === "WEBP") return "image/webp";
  if (bytes.length >= 6 && /^GIF8[79]a$/.test(bytes.toString("latin1", 0, 6))) return "image/gif";
  return null;
}

/** Whether [bytes] are MP3: an ID3 tag, or an MPEG audio frame header. */
export function isMp3(bytes: Buffer): boolean {
  if (bytes.length >= 3 && bytes.toString("latin1", 0, 3) === "ID3") return true;
  return bytes.length >= 2 && bytes[0] === 0xff && (bytes[1]! & 0xe0) === 0xe0;
}

export type Proof = { ok: true; detail: string } | { ok: false; detail: string; nextStep: string };

const wrongOutput = (detail: string, capability: string): Proof => ({
  ok: false,
  detail,
  nextStep: `The model answered, but not with usable ${capability}: it probably can't do this. Pick another model for this route.`,
});

/** What a verification answer proves, in words that hold no content. */
export function proofOf(capability: Capability, r: InvocationResult): Proof {
  if ("chat" in r) {
    const text = r.chat.text.trim();
    if (!text) return wrongOutput("the answer was empty", "text");
    if (capability === "vision" && !RED_WORDS.test(text)) return wrongOutput("answered, but didn't name the test image's colour", "image understanding");
    return { ok: true, detail: `answered (${r.chat.outputTokens ?? "?"} tokens)` };
  }
  if ("transcript" in r) {
    // The test audio is silence: an empty transcript is the right answer, so the proof is a well-formed result.
    if (typeof r.transcript.text !== "string") return wrongOutput("no transcript in the answer", "transcripts");
    return { ok: true, detail: `transcribed (timings: ${r.transcript.timing})` };
  }
  if ("embeddings" in r) {
    const v = r.embeddings.vectors;
    const dims = v[0]?.length ?? 0;
    if (v.length !== EMBED_INPUTS.length || dims === 0) return wrongOutput("no vector for the input", "embeddings");
    if (!v.every((x) => x.length === dims && x.every(Number.isFinite))) return wrongOutput("the vectors were malformed", "embeddings");
    return { ok: true, detail: `${dims} dimensions` };
  }
  const bytes = r.binary.bytes;
  if (capability === "speech") return isMp3(bytes) ? { ok: true, detail: `audio/mpeg, ${bytes.length} bytes` } : wrongOutput(`${bytes.length} bytes that aren't MP3 audio`, "speech");
  const kind = sniffImage(bytes);
  return kind ? { ok: true, detail: `${kind}, ${bytes.length} bytes` } : wrongOutput(`${bytes.length} bytes that aren't an image`, "images");
}

/** What to do after a verification request failed at the provider. */
export function nextStepFor(e: ProviderError): string {
  switch (e.kind) {
    case "auth":
      return "The provider refused the key: replace it under Providers & keys, or give the key's project access to this model.";
    case "bad_request":
      return "The provider rejected the request: the model id may be wrong, not available to this key, or unable to do this. Check the id against the connection test's list, or pick another model.";
    case "rate_limited":
      return "Rate limited, or the account is out of credit: check billing and limits in the provider's dashboard, then verify again.";
    case "timeout":
      return "The provider didn't answer in time: verify again, or raise the provider's timeout under Providers & keys.";
    case "network":
    case "server":
      return "The provider had a problem: verify again in a few minutes.";
    default:
      return "Verify again; if it keeps failing, test the provider's connection under Providers & keys.";
  }
}
