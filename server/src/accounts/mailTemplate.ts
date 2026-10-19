import { MAIL_LOGO_PNG_BASE64 } from "./mailLogo.js";

/**
 * The layout of account emails: the Meldiq logo and name, one card with a heading, short paragraphs and (for codes) the
 * code in large type, then a footer. Every email has a plain-text twin built from the same parts, so the two never drift.
 *
 * Rules that keep these emails safe to trust: no links and no remote images (the logo travels inside the message, so
 * nothing is fetched and nothing tracks the reader), and nothing anyone typed. Values passed in are escaped anyway.
 * Colours are Graphite light (docs/DESIGN_SYSTEM.md), with Graphite dark for clients that honour prefers-color-scheme.
 */

/** Content-ID of the embedded logo; the HTML refers to it as cid:… */
export const MAIL_LOGO_CID = "logo@meldiq.app";

export interface InlineImage {
  cid: string;
  filename: string;
  contentType: string;
  content: Buffer;
}

export interface MailCode {
  value: string;
  /** Shown under the code, e.g. when it expires. */
  note: string;
}

export interface MailContent {
  /** Inbox preview line (hidden in the body). */
  preview: string;
  heading: string;
  /** Short paragraphs; `**x**` marks bold text. */
  paragraphs: string[];
  code?: MailCode;
  /** Smaller text under the divider: what to do if the reader didn't ask for this. */
  footnote: string;
}

export interface RenderedMail {
  text: string;
  html: string;
  inline: InlineImage[];
}

const FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
const MONO = "'SF Mono',SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace";
const LIGHT = { canvas: "#F4F5F7", card: "#FFFFFF", inset: "#EBECEF", border: "#DCDEE3", t1: "#17181C", t2: "#4A4D57", t3: "#5D616C", accent: "#4B4FC9" };
const DARK = { canvas: "#1E1F24", card: "#26282E", inset: "#2F3138", border: "#383B43", t1: "#ECEDF0", t2: "#B6BAC3", t3: "#989DA8" };
/**
 * Email can't run scripts, so there is no real copy button: one tap selects the whole code (user-select: all, where
 * the client supports it), and Gmail adds its own Copy button when it recognises a code.
 */
const SELECT_HINT = "Tap the code to select it.";
const SECURITY_LINE ="Meldiq will never ask you for a code by phone, chat or email.";
const FOOTER = "This is an automated message from Meldiq. Replies to this address aren't read.";

let logo: InlineImage | null = null;
function logoImage(): InlineImage {
  logo ??= { cid: MAIL_LOGO_CID, filename: "meldiq.png", contentType: "image/png", content: Buffer.from(MAIL_LOGO_PNG_BASE64, "base64") };
  return logo;
}

export function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

/** Escapes, then turns `**x**` into bold. */
function inline(s: string): string {
  return escapeHtml(s).replace(/\*\*(.+?)\*\*/g, `<strong style="color:${LIGHT.t1};font-weight:600" class="t1">$1</strong>`);
}

const plain = (s: string): string => s.replace(/\*\*(.+?)\*\*/g, "$1");

export function renderText(c: MailContent): string {
  const parts = ["Hello,", ...c.paragraphs.map(plain)];
  if (c.code) parts.push(`    ${c.code.value}`, c.code.note);
  parts.push(plain(c.footnote));
  if (c.code) parts.push(SECURITY_LINE);
  parts.push(`— Meldiq\n${FOOTER}`);
  return parts.join("\n\n");
}

function codeBlock(code: MailCode): string {
  return `
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:24px 0 8px">
  <tr><td align="center" class="inset" style="background:${LIGHT.inset};border:1px solid ${LIGHT.border};border-radius:12px;padding:18px 12px;font-family:${MONO};font-size:32px;line-height:1.2;font-weight:700;letter-spacing:6px;color:${LIGHT.t1}"><span class="t1" style="-webkit-user-select:all;user-select:all;color:${LIGHT.t1}">${escapeHtml(code.value)}</span></td></tr>
</table>
<p class="t3" style="margin:0 0 4px;text-align:center;font-family:${FONT};font-size:13px;line-height:1.5;color:${LIGHT.t3}">${inline(code.note)} ${escapeHtml(SELECT_HINT)}</p>`;
}

const DARK_CSS = `
@media (prefers-color-scheme: dark) {
  .canvas { background:${DARK.canvas} !important; }
  .card { background:${DARK.card} !important; border-color:${DARK.border} !important; }
  .inset { background:${DARK.inset} !important; border-color:${DARK.border} !important; color:${DARK.t1} !important; }
  .rule { border-color:${DARK.border} !important; }
  .t1 { color:${DARK.t1} !important; }
  .t2 { color:${DARK.t2} !important; }
  .t3 { color:${DARK.t3} !important; }
}`;

export function renderHtml(subject: string, c: MailContent): string {
  const paragraphs = c.paragraphs
    .map((p) => `<p class="t2" style="margin:0 0 14px;font-family:${FONT};font-size:15px;line-height:1.6;color:${LIGHT.t2}">${inline(p)}</p>`)
    .join("\n");
  const security = c.code ? `<p class="t3" style="margin:12px 0 0;font-family:${FONT};font-size:13px;line-height:1.5;color:${LIGHT.t3}">${escapeHtml(SECURITY_LINE)}</p>` : "";
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="supported-color-schemes" content="light dark">
<title>${escapeHtml(subject)}</title>
<style>${DARK_CSS}</style>
</head>
<body class="canvas" style="margin:0;padding:0;background:${LIGHT.canvas}">
<div style="display:none;max-height:0;overflow:hidden;opacity:0">${escapeHtml(c.preview)}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" class="canvas" style="background:${LIGHT.canvas}">
<tr><td align="center" style="padding:32px 16px">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:480px">
  <tr><td style="padding:0 4px 18px">
    <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
      <td style="vertical-align:middle"><img src="cid:${MAIL_LOGO_CID}" width="40" height="40" alt="Meldiq" style="display:block;border:0;border-radius:10px"></td>
      <td class="t1" style="vertical-align:middle;padding-left:10px;font-family:${FONT};font-size:19px;font-weight:600;letter-spacing:0.2px;color:${LIGHT.t1}">Meldiq</td>
    </tr></table>
  </td></tr>
  <tr><td class="card" style="background:${LIGHT.card};border:1px solid ${LIGHT.border};border-top:4px solid ${LIGHT.accent};border-radius:16px;padding:30px 28px 26px">
    <h1 class="t1" style="margin:0 0 14px;font-family:${FONT};font-size:22px;line-height:1.3;font-weight:600;color:${LIGHT.t1}">${escapeHtml(c.heading)}</h1>
    ${paragraphs}
    ${c.code ? codeBlock(c.code) : ""}
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="margin:22px 0 0"><tr><td class="rule" style="border-top:1px solid ${LIGHT.border};font-size:0;line-height:0">&nbsp;</td></tr></table>
    <p class="t3" style="margin:16px 0 0;font-family:${FONT};font-size:13px;line-height:1.5;color:${LIGHT.t3}">${inline(c.footnote)}</p>
    ${security}
  </td></tr>
  <tr><td class="t3" style="padding:18px 4px 0;font-family:${FONT};font-size:12px;line-height:1.5;color:${LIGHT.t3}">${escapeHtml(FOOTER)}</td></tr>
</table>
</td></tr>
</table>
</body>
</html>`;
}

export function renderMail(subject: string, c: MailContent): RenderedMail {
  return { text: renderText(c), html: renderHtml(subject, c), inline: [logoImage()] };
}
