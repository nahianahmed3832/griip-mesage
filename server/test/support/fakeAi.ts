import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { vi } from "vitest";

/**
 * A fake OpenAI, Anthropic and Google for AI Workforce tests: global fetch is wrapped so requests to the providers'
 * fixed hosts get scripted answers and everything else (the test server) goes through. Nothing leaves this machine.
 * These tests prove the runtime around the providers; they are not tests of a real provider.
 */
export interface FakeCall {
  url: string;
  method: string;
  body: string | FormData | undefined;
  json: Record<string, unknown> | null;
}

export type Answer = { status: number; body?: unknown; bytes?: Buffer };
export type Script = (c: FakeCall) => Answer | null;

const HOSTS = ["https://api.openai.com", "https://api.anthropic.com", "https://generativelanguage.googleapis.com"];

export function fakeProviders(script: Script) {
  const calls: FakeCall[] = [];
  const real = globalThis.fetch;
  const wrapped = async (input: string | URL | Request, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    if (!HOSTS.some((h) => url.startsWith(h))) return real(input, init);
    const body = init?.body as string | FormData | undefined;
    let json: Record<string, unknown> | null = null;
    if (typeof body === "string") {
      try {
        json = JSON.parse(body) as Record<string, unknown>;
      } catch {
        json = null;
      }
    }
    const c: FakeCall = { url, method: init?.method ?? "GET", body, json };
    calls.push(c);
    const a = script(c) ?? { status: 500, body: { error: "unscripted" } };
    return {
      status: a.status,
      json: async () => a.body ?? {},
      arrayBuffer: async () => {
        const b = a.bytes ?? Buffer.alloc(0);
        return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
      },
    } as unknown as Response;
  };
  vi.stubGlobal("fetch", wrapped);
  return { calls, restore: () => vi.unstubAllGlobals() };
}

/** The text of an OpenAI chat request's system and user messages. */
export function chatText(c: FakeCall): { system: string; user: string } {
  const messages = (c.json?.messages ?? []) as { role: string; content: string | { type: string; text?: string }[] }[];
  const text = (m: (typeof messages)[number]) => (typeof m.content === "string" ? m.content : m.content.map((p) => p.text ?? "").join(""));
  return { system: messages.filter((m) => m.role === "system").map(text).join("\n"), user: messages.filter((m) => m.role === "user").map(text).join("\n") };
}

export const openAiChat = (content: string, tokens = { in: 120, out: 60 }): Answer => ({
  status: 200,
  body: { choices: [{ message: { content } }], usage: { prompt_tokens: tokens.in, completion_tokens: tokens.out } },
});

export const openAiModels = (ids: string[]): Answer => ({ status: 200, body: { data: ids.map((id) => ({ id })) } });

export function hasFfmpeg(): boolean {
  try {
    execFileSync("ffmpeg", ["-hide_banner", "-version"], { stdio: "ignore" });
    execFileSync("ffprobe", ["-hide_banner", "-version"], { stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}

/** A real MP4 (test pattern and a tone) of [seconds], made by ffmpeg. */
export function testVideo(seconds: number, pattern: "testsrc" | "smptebars" = "testsrc", tone = 440): Buffer {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "meldiq-video-"));
  const out = path.join(dir, "v.mp4");
  execFileSync(
    "ffmpeg",
    ["-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", `${pattern}=size=320x240:rate=25:duration=${seconds}`, "-f", "lavfi", "-i", `sine=frequency=${tone}:duration=${seconds}`, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out],
    { stdio: "ignore" },
  );
  const b = fs.readFileSync(out);
  fs.rmSync(dir, { recursive: true, force: true });
  return b;
}

/** The duration ffprobe reads from an MP4. */
export function probeSeconds(bytes: Buffer): number {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "meldiq-probe-"));
  const f = path.join(dir, "x.mp4");
  fs.writeFileSync(f, bytes);
  const out = execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f]).toString().trim();
  fs.rmSync(dir, { recursive: true, force: true });
  return Number(out);
}

/** A 1×1 PNG. */
export const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==", "base64");
