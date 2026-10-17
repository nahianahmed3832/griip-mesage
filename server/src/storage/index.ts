import type { Config, StorageBackend } from "../config.js";
import { type BlobStore, LocalBlobStore } from "./blobstore.js";
import { FallbackBlobStore } from "./fallback.js";
import { S3BlobStore } from "./s3.js";

export type { BlobKind, BlobStore } from "./blobstore.js";

export function createBlobStore(config: Config): BlobStore {
  const primary = storeFor(config, config.storage.backend);
  const fallback = config.storage.readFallback;
  return fallback ? new FallbackBlobStore(primary, storeFor(config, fallback)) : primary;
}

function storeFor(config: Config, backend: StorageBackend): BlobStore {
  if (backend === "s3") {
    if (!config.storage.s3) throw new Error("S3 storage isn't configured");
    return new S3BlobStore(config.storage.s3);
  }
  return new LocalBlobStore({ attachments: config.attachmentDir, "profile-photos": config.profilePhotoDir });
}
