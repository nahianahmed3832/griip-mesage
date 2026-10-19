import { connectorDef } from "../catalog.js";
import type { Connector, ConnectorDeps } from "../connector.js";

/** Incoming webhooks as a connector (src/integrations/hooks.ts does the work): no provider account to sign in to. */
export const HOOKS_CONNECTOR = "incoming-webhooks";

export function incomingConnector(deps: ConnectorDeps): Connector {
  return {
    def: connectorDef(HOOKS_CONNECTOR)!,
    oauth: null,
    configured: () => !!deps.config.publicUrl && !!deps.config.keyring,
    identify: async () => ({ externalId: HOOKS_CONNECTOR, label: null }),
  };
}
