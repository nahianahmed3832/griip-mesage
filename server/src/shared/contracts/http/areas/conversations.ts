import { adminRoutes } from "../admin.js";
import { conversationRoutes } from "../conversations.js";
import { experimentRoutes } from "../experiments.js";
import { federationRoutes } from "../federation.js";
import { meetingRoutes } from "../meetings.js";
import type { RouteContract } from "../route.js";
import { searchRoutes } from "../search.js";

/**
 * Route contracts of the conversations area (docs/architecture/ARCHITECTURE_1_0_PLAN.md §11, step C2): conversations,
 * search, meetings, platform operations, experiments and federation's client routes.
 */
export const conversationsRoutes: readonly RouteContract[] = [
  ...conversationRoutes,
  ...searchRoutes,
  ...meetingRoutes,
  ...adminRoutes,
  ...experimentRoutes,
  ...federationRoutes,
];
