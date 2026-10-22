import type { Collection } from "mongodb";
import { RECOVERY_TICKET_GRACE_MS, type RecoveryCodeRow, type RecoveryStore, type RecoveryTicketRow } from "./recoveryStore.js";
import type { MeldiqMongo } from "../../infrastructure/database/mongo/client.js";

type CodeDoc = RecoveryCodeRow & { _id: string };
type TicketDoc = RecoveryTicketRow & { _id: string };

function strip<T extends { _id: unknown }>(d: T | null): Omit<T, "_id"> | undefined {
  if (!d) return undefined;
  const { _id: _ignored, ...row } = d;
  return row;
}

/**
 * Recovery codes in MongoDB (MONGODB_DOMAINS includes "accounts"), in collections named like the SQLite tables. Using a
 * code or a ticket is one conditional single-document update, so concurrent requests can't both win. Replacing a set
 * deletes and inserts; the codes are random, so a crash between the two leaves at worst an account without codes.
 */
export class MongoRecoveryStore implements RecoveryStore {
  private readonly codesCol: Collection<CodeDoc>;
  private readonly tickets: Collection<TicketDoc>;

  constructor(mongo: MeldiqMongo) {
    this.codesCol = mongo.db.collection<CodeDoc>("account_recovery_codes");
    this.tickets = mongo.db.collection<TicketDoc>("account_recovery_tickets");
  }

  async replaceCodes(accountId: string, rows: RecoveryCodeRow[]): Promise<void> {
    await this.codesCol.deleteMany({ account_id: accountId });
    if (rows.length) await this.codesCol.insertMany(rows.map((r) => ({ _id: r.id, ...r, account_id: accountId, used_at: null })));
  }

  async deleteCodes(accountId: string): Promise<number> {
    return (await this.codesCol.deleteMany({ account_id: accountId })).deletedCount;
  }

  async deleteForAccount(accountId: string): Promise<void> {
    await this.codesCol.deleteMany({ account_id: accountId });
    await this.tickets.deleteMany({ account_id: accountId });
  }

  async codes(accountId: string): Promise<RecoveryCodeRow[]> {
    const docs = await this.codesCol.find({ account_id: accountId }).sort({ created_at: 1, _id: 1 }).toArray();
    return docs.map((d) => strip(d)!);
  }

  async useCode(accountId: string, codeHash: string, at: number): Promise<boolean> {
    const res = await this.codesCol.updateOne({ account_id: accountId, code_hash: codeHash, used_at: null }, { $set: { used_at: at } });
    return res.modifiedCount === 1;
  }

  async insertTicket(r: RecoveryTicketRow): Promise<void> {
    await this.tickets.insertOne({ _id: r.ticket_hash, ...r, attempts: 0, used_at: null });
  }

  async ticket(hash: string): Promise<RecoveryTicketRow | undefined> {
    return strip(await this.tickets.findOne({ _id: hash }));
  }

  async countTicketAttempt(hash: string): Promise<void> {
    await this.tickets.updateOne({ _id: hash }, { $inc: { attempts: 1 } });
  }

  async useTicket(hash: string, at: number): Promise<boolean> {
    const res = await this.tickets.updateOne({ _id: hash, used_at: null, expires_at: { $gt: at } }, { $set: { used_at: at } });
    return res.modifiedCount === 1;
  }

  async purgeExpired(at: number): Promise<number> {
    return (await this.tickets.deleteMany({ expires_at: { $lte: at - RECOVERY_TICKET_GRACE_MS } })).deletedCount;
  }
}
