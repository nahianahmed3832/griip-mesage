import type { Collection } from "mongodb";
import type { KeyBackupRow, KeyBackupStore, KeyUnlockRow } from "../accounts/keyBackupStore.js";
import type { MeldiqMongo } from "./client.js";

type BackupDoc = KeyBackupRow & { _id: string };
type UnlockDoc = KeyUnlockRow & { _id: string };

function strip<T extends { _id: unknown }>(d: T | null): Omit<T, "_id"> | undefined {
  if (!d) return undefined;
  const { _id: _ignored, ...row } = d;
  return row;
}

/**
 * Key backups in MongoDB (MONGODB_DOMAINS includes "accounts"), in collections named like the SQLite tables and keyed
 * by account id. Using an unlock code is one conditional delete, so concurrent requests can't both win.
 */
export class MongoKeyBackupStore implements KeyBackupStore {
  private readonly backups: Collection<BackupDoc>;
  private readonly unlocks: Collection<UnlockDoc>;

  constructor(mongo: MeldiqMongo) {
    this.backups = mongo.db.collection<BackupDoc>("account_key_backups");
    this.unlocks = mongo.db.collection<UnlockDoc>("account_key_unlocks");
  }

  async backup(accountId: string): Promise<KeyBackupRow | undefined> {
    return strip(await this.backups.findOne({ _id: accountId }));
  }

  async putBackup(r: KeyBackupRow): Promise<void> {
    const { created_at, ...rest } = r;
    await this.backups.updateOne({ _id: r.account_id }, { $set: rest, $setOnInsert: { created_at } }, { upsert: true });
  }

  async deleteBackup(accountId: string): Promise<boolean> {
    return (await this.backups.deleteOne({ _id: accountId })).deletedCount === 1;
  }

  async putUnlock(r: KeyUnlockRow): Promise<void> {
    await this.unlocks.replaceOne({ _id: r.account_id }, { ...r, attempts: 0 }, { upsert: true });
  }

  async unlock(accountId: string): Promise<KeyUnlockRow | undefined> {
    return strip(await this.unlocks.findOne({ _id: accountId }));
  }

  async countUnlockAttempt(accountId: string): Promise<void> {
    await this.unlocks.updateOne({ _id: accountId }, { $inc: { attempts: 1 } });
  }

  async useUnlock(accountId: string, codeHash: string): Promise<boolean> {
    return (await this.unlocks.deleteOne({ _id: accountId, code_hash: codeHash })).deletedCount === 1;
  }

  async deleteForAccount(accountId: string): Promise<void> {
    await this.backups.deleteOne({ _id: accountId });
    await this.unlocks.deleteOne({ _id: accountId });
  }

  async purgeExpired(at: number): Promise<number> {
    return (await this.unlocks.deleteMany({ expires_at: { $lte: at } })).deletedCount;
  }
}
