/**
 * The console's two cookies. `__Host-` makes browsers insist on Secure, Path=/ and no Domain, so the cookie belongs to
 * the console's host alone: web.meldiq.app and msg.meldiq.app never receive it. SameSite=Strict keeps it off every
 * request another site starts. Values are random base64url, so no escaping is needed.
 */
export const SESSION_COOKIE = "__Host-mdq_admin";
/** Between the password step and the passkey step (and during enrolment): the ceremony's id. */
export const PENDING_COOKIE = "__Host-mdq_admin_pending";

const VALUE = /^[A-Za-z0-9_-]{16,128}$/;

/** The value of cookie [name] in a Cookie header, if it is well formed. */
export function readCookie(header: string | undefined, name: string): string | undefined {
  if (!header) return undefined;
  for (const part of header.split(";")) {
    const eq = part.indexOf("=");
    if (eq < 0 || part.slice(0, eq).trim() !== name) continue;
    const value = part.slice(eq + 1).trim();
    return VALUE.test(value) ? value : undefined;
  }
  return undefined;
}

export function setCookie(name: string, value: string, maxAgeSeconds: number): string {
  return `${name}=${value}; Max-Age=${Math.max(Math.floor(maxAgeSeconds), 0)}; Path=/; Secure; HttpOnly; SameSite=Strict`;
}

export function clearCookie(name: string): string {
  return `${name}=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Strict`;
}
