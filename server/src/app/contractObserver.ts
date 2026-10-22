import type { FastifyInstance } from "fastify";
import type { ServerEvent } from "../shared/contracts/realtime.js";

/**
 * Sees every route, every answer and every WebSocket frame, so the test suite can check them against the contracts in
 * src/shared/contracts (test/support/contracts.ts). buildApp installs it only when one is passed; production never
 * does.
 */
export interface ContractObserver {
  route(method: string, url: string): void;
  /** [url]: the route's template ("/v1/calls/:id"), or null when no route matched. */
  response(method: string, url: string | null, status: number, body: string, contentType: string | undefined): void;
  frame(e: ServerEvent): void;
}

/** Must run before any route is registered (onRoute only sees later routes). */
export function observeContracts(app: FastifyInstance, observer: ContractObserver): void {
  app.addHook("onRoute", (r) => {
    for (const m of [r.method].flat()) observer.route(String(m), r.url);
  });
  app.addHook("onSend", async (req, reply, payload) => {
    if (typeof payload === "string") {
      const type = reply.getHeader("content-type");
      observer.response(req.method, req.routeOptions?.url ?? null, reply.statusCode, payload, typeof type === "string" ? type : undefined);
    }
    return payload;
  });
}
