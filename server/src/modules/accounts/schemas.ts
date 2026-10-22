import { z } from "zod";
import { zKey, zName, zSig } from "../../app/context.js";
import { PASSWORD_MAX_LENGTH } from "./passwords.js";

/**
 * Request bodies of the v2 account API (docs/ORBIT_2_ARCHITECTURE §6). These are the contract: the Android models in
 * data/account/AccountModels.kt mirror them, and future clients implement the same ones.
 */

/** Lower-cased and trimmed; a deliberately loose shape check: the verification email is the real test. */
export const zEmail = z
  .string()
  .trim()
  .toLowerCase()
  .max(254)
  .regex(/^[^\s@]+@[^\s@]+\.[^\s@]+$/, "Enter an email address like name@example.com");

/** Length rules are checked with a clear message by passwordProblem(); this only bounds the request. */
export const zPassword = z.string().min(1).max(PASSWORD_MAX_LENGTH * 4);

export const zDeviceKeys = z.object({ name: zName, signingKey: zKey, encryptionKey: zKey, certificate: zSig });

export const zDeviceProof = z.object({ deviceId: z.string().min(3).max(64), nonce: z.string().min(16).max(64), signature: zSig });

export const zVerifyCode = z.string().trim().min(4).max(32);

export const registerBody = z.object({ name: zName, email: zEmail, password: zPassword, inviteCode: z.string().trim().min(8).max(40).optional() });
export const emailBody = z.object({ email: zEmail });
/** The password chosen at sign-up goes with the code: whoever verifies an address is whoever set its password. */
export const verifyEmailBody = z.object({ email: zEmail, code: zVerifyCode, password: zPassword });
export const loginBody = z.object({ email: zEmail, password: zPassword, deviceName: zName.optional(), device: zDeviceProof.optional() });
export const refreshBody = z.object({ refreshToken: z.string().min(20).max(200) });
export const resetPasswordBody = z.object({ email: zEmail, code: zVerifyCode, newPassword: zPassword });
/** Base64 of exactly [bytes] bytes (with padding). */
const zB64 = (bytes: number) => z.string().length(4 * Math.ceil(bytes / 3)).regex(/^[A-Za-z0-9+/]+={0,2}$/);

/**
 * The identity seed sealed under the password (docs/KEY_BACKUP.md): PBKDF2-HMAC-SHA256 with [iterations] over a 16-byte
 * salt, AES-256-GCM with a 12-byte nonce over the 32-byte seed (48 bytes with the tag).
 */
export const zKeyBackup = z.object({
  version: z.literal(1),
  iterations: z.number().int().min(300_000).max(5_000_000),
  salt: zB64(16),
  nonce: zB64(12),
  ciphertext: zB64(48),
  identityKey: zKey,
});
export const putKeyBackupBody = z.object({ password: zPassword, backup: zKeyBackup });
export const keyUnlockBody = z.object({ code: zVerifyCode });
/** [keyBackup]: the keys sealed again under the new password, so a backup survives the change. */
export const changePasswordBody = z.object({ currentPassword: zPassword, newPassword: zPassword, keyBackup: zKeyBackup.optional() });
export const updateAccountBody = z.object({ name: zName });
export const preferencesBody = z.object({ selectedWorkspaceId: z.string().min(3).max(64) });

/** Recovery codes (docs/ACCOUNT_RECOVERY.md): making or revoking a set needs the password again. */
export const recoveryPasswordBody = z.object({ password: zPassword });

/** Deleting your account (docs/ACCOUNT_DELETION.md): the password again, and the account's email typed by the person. */
export const deleteAccountBody = z.object({ password: zPassword, confirmEmail: zEmail });
export const zRecoveryCode = z.string().trim().min(8).max(40);
export const recoveryStartBody = z.object({ email: zEmail, code: zRecoveryCode, newEmail: zEmail });
export const recoveryCompleteBody = z.object({ ticket: z.string().min(20).max(200), code: zVerifyCode, newPassword: zPassword });

export const enrollDeviceBody = z.object({
  identityKey: zKey.optional(),
  username: z.string().trim().max(40).optional(),
  device: zDeviceKeys,
  nonce: z.string().min(16).max(64),
  signature: zSig,
});
export const resetIdentityBody = z.object({ password: zPassword, identityKey: zKey, device: zDeviceKeys, nonce: z.string().min(16).max(64), signature: zSig });

export const legacyVerifyBody = z.object({ username: z.string().trim().toLowerCase().min(1).max(40), nonce: z.string().min(16).max(64), signature: zSig });
export const legacyCompleteBody = z.object({ ticket: z.string().min(20).max(200), name: zName, email: zEmail, password: zPassword });

export type DeviceKeys = z.infer<typeof zDeviceKeys>;
