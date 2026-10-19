import type { Collection } from "mongodb";
import { type NavPrefs, type StoredPrefs, zNavPrefs } from "../preferences/schema.js";
import type { PreferenceStore, PrefsWrite } from "../preferences/store.js";
import { isDuplicateKey, type OrbitMongo } from "./client.js";

/** The nav_preferences collection: the SQLite row's fields, with the document embedded instead of JSON text. */
interface PrefsDoc {
  _id: { user_id: string; workspace_id: string };
  user_id: string;
  workspace_id: string;
  doc: NavPrefs;
  rev: number;
  updated_at: number;
}

function fromDoc(d: PrefsDoc): StoredPrefs {
  const parsed = zNavPrefs.safeParse(d.doc);
  const prefs = parsed.success ? parsed.data : { starred: [], sections: [], collapsed: [], notify: {} };
  return { userId: d.user_id, workspaceId: d.workspace_id, prefs, rev: d.rev, updatedAt: d.updated_at };
}

/**
 * Personal navigation preferences in MongoDB (MONGODB_DOMAINS includes "preferences"). Every write is one atomic
 * single-document operation conditioned on the revision, so no transaction is needed and two devices can't overwrite
 * each other.
 */
export class MongoPreferenceStore implements PreferenceStore {
  readonly backend = "mongodb" as const;
  private readonly col: Collection<PrefsDoc>;

  constructor(mongo: OrbitMongo) {
    this.col = mongo.db.collection<PrefsDoc>("nav_preferences");
  }

  async get(userId: string, workspaceId: string): Promise<StoredPrefs | null> {
    const d = await this.col.findOne({ _id: { user_id: userId, workspace_id: workspaceId } });
    return d ? fromDoc(d) : null;
  }

  async put(userId: string, workspaceId: string, prefs: NavPrefs, baseRev: number, at: number): Promise<PrefsWrite> {
    const _id = { user_id: userId, workspace_id: workspaceId };
    const rev = baseRev + 1;
    if (baseRev === 0) {
      try {
        await this.col.insertOne({ _id, user_id: userId, workspace_id: workspaceId, doc: prefs, rev, updated_at: at });
        return { ok: true, doc: { userId, workspaceId, prefs, rev, updatedAt: at } };
      } catch (e) {
        if (!isDuplicateKey(e)) throw e;
        return { ok: false, current: await this.get(userId, workspaceId) };
      }
    }
    const updated = await this.col.findOneAndUpdate({ _id, rev: baseRev }, { $set: { doc: prefs, rev, updated_at: at } }, { returnDocument: "after" });
    if (updated) return { ok: true, doc: fromDoc(updated) };
    return { ok: false, current: await this.get(userId, workspaceId) };
  }

  async deleteForMember(userId: string, workspaceId: string): Promise<void> {
    await this.col.deleteOne({ _id: { user_id: userId, workspace_id: workspaceId } });
  }

  async deleteForWorkspace(workspaceId: string): Promise<void> {
    await this.col.deleteMany({ workspace_id: workspaceId });
  }

  async deleteForUser(userId: string): Promise<void> {
    await this.col.deleteMany({ user_id: userId });
  }
}
