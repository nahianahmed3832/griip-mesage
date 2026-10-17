import type { Config } from "../config.js";
import { type BlobStore, LocalBlobStore } from "./blobstore.js";
import { S3BlobStore } from "./s3.js";

export type { BlobKind, BlobStore } from "./blobstore.js";

export function createBlobStore(config: Config): BlobStore {
  if (config.storage.backend === "s3") return new S3BlobStore(config.storage.s3);
  return new LocalBlobStore({ attachments: config.attachmentDir, "profile-photos": config.profilePhotoDir });
}
