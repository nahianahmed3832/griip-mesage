import { orgRoutes } from "../orgs.js";
import type { RouteContract } from "../route.js";
import { roleRoutes } from "../roles.js";

/** Route contracts of the organizations area (docs/architecture/ARCHITECTURE_1_0_PLAN.md §11, step C2): communities and roles. */
export const organizationsRoutes: readonly RouteContract[] = [...orgRoutes, ...roleRoutes];
