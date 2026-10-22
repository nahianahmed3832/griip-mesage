/**
 * The API's error type and its uniform envelope `{ error: { code, message, details? } }`, written by the error handler
 * in app/server.ts (docs/architecture/ARCHITECTURE_1_0_PLAN.md §4).
 */
export class ApiError extends Error {
  constructor(
    public statusCode: number,
    public code: string,
    message: string,
    public details?: unknown,
  ) {
    super(message);
  }
}

export const badRequest = (msg: string, details?: unknown) => new ApiError(400, "bad_request", msg, details);
export const unauthorized = (msg = "Authentication required") => new ApiError(401, "unauthorized", msg);
export const forbidden = (msg = "You do not have permission to do that") => new ApiError(403, "forbidden", msg);
export const notFound = (msg = "Not found") => new ApiError(404, "not_found", msg);
export const conflict = (msg: string, details?: unknown) => new ApiError(409, "conflict", msg, details);
