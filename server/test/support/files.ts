import type { AppContext } from "../../src/app/context.js";

/**
 * Test-only changes to the files module's records, made in whichever store the server uses (SQLite, or MongoDB with
 * the "files" domain), so suites behave the same under MELDIQ_TEST_MONGO_DOMAINS.
 */
type H = { ctx: AppContext };
const mongo = (h: H) => (h.ctx.files.backend === "mongodb" ? h.ctx.mongo!.db : null);

/** Makes attachments expire at [at] (in the past, to have retention remove them). */
export async function setAttachmentExpiry(h: H, ids: string[], at: number): Promise<void> {
  const m = mongo(h);
  if (m) await m.collection("attachments").updateMany({ _id: { $in: ids as never[] } }, { $set: { expires_at: at } });
  else for (const id of ids) h.ctx.db.prepare("UPDATE attachments SET expires_at = ? WHERE id = ?").run(at, id);
}

/** Ends direct-upload sessions now. */
export async function expireUploadSessions(h: H, ids: string[]): Promise<void> {
  const m = mongo(h);
  if (m) await m.collection("attachment_uploads").updateMany({ _id: { $in: ids as never[] } }, { $set: { expires_at: 0 } });
  else for (const id of ids) h.ctx.db.prepare("UPDATE attachment_uploads SET expires_at = 0 WHERE id = ?").run(id);
}

/** Open direct-upload sessions of a person (expired or not). */
export async function uploadSessionCount(h: H, userId: string): Promise<number> {
  return (await h.ctx.files.pendingUsage({ uploaders: [userId] })).count;
}

/** Attachments a person uploaded (expired or not). */
export async function attachmentCount(h: H, userId: string): Promise<number> {
  return (await h.ctx.files.storedUsage({ uploaders: [userId] })).count;
}
