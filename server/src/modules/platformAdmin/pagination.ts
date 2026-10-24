import { badRequest } from "../../shared/errors.js";

/** Keyset cursors: the last row's (created_at, id), opaque to the console. */
export interface Position {
  createdAt: number;
  id: string;
}

export function encodeCursor(p: Position): string {
  return Buffer.from(`${p.createdAt}.${p.id}`).toString("base64url");
}

export function decodeCursor(cursor: string | undefined): Position | undefined {
  if (!cursor) return undefined;
  const text = Buffer.from(cursor, "base64url").toString("utf8");
  const dot = text.indexOf(".");
  const createdAt = Number(text.slice(0, dot));
  const id = text.slice(dot + 1);
  if (dot <= 0 || !Number.isSafeInteger(createdAt) || !/^[A-Za-z0-9_-]{1,64}$/.test(id)) throw badRequest("That page link isn't valid. Start from the first page.");
  return { createdAt, id };
}

/** Asks for one row more than [limit], so the answer knows whether another page follows. */
export function page<T extends { created_at: number; id: string }>(rows: T[], limit: number): { rows: T[]; nextCursor: string | null } {
  if (rows.length <= limit) return { rows, nextCursor: null };
  const shown = rows.slice(0, limit);
  const last = shown[shown.length - 1];
  return { rows: shown, nextCursor: encodeCursor({ createdAt: last.created_at, id: last.id }) };
}
