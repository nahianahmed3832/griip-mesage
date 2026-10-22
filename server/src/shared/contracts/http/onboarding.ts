import { z } from "zod/v4";
import { GUIDE_LIMITS, Guide, OrgParamsNonEmpty } from "./community.js";
import { zTime } from "./primitives.js";
import { route } from "./route.js";

/**
 * What a community shows newcomers (batch B8b): onboarding questions, whose answers give roles and open public
 * channels (never roles with administrative permissions), and the community guide. Plaintext configuration, like the
 * community's rules.
 */

export const ONBOARDING_LIMITS = { prompts: 5, options: 10, title: 100, rolesPerOption: 5, channelsPerOption: 10 } as const;

const zRefId = z.string().min(1).max(64);

export const OnboardingOptionBody = z.object({
  /** Kept when it's 1–32 of A-Z, a-z, 0-9, _ and -; a new id otherwise. */
  id: z.string().max(32).optional(),
  title: z.string().trim().min(1).max(ONBOARDING_LIMITS.title),
  emoji: z.string().trim().max(16).nullish(),
  roleIds: z.array(zRefId).max(ONBOARDING_LIMITS.rolesPerOption).default([]),
  channelIds: z.array(zRefId).max(ONBOARDING_LIMITS.channelsPerOption).default([]),
});
export const OnboardingPromptBody = z.object({
  id: z.string().max(32).optional(),
  title: z.string().trim().min(1).max(ONBOARDING_LIMITS.title),
  single: z.boolean().default(false),
  required: z.boolean().default(false),
  options: z.array(OnboardingOptionBody).min(1).max(ONBOARDING_LIMITS.options),
});
export const OnboardingBody = z.strictObject({ enabled: z.boolean(), prompts: z.array(OnboardingPromptBody).max(ONBOARDING_LIMITS.prompts) });
export const OnboardingAnswersBody = z.strictObject({ optionIds: z.array(z.string().max(32)).max(ONBOARDING_LIMITS.prompts * ONBOARDING_LIMITS.options) });

export const OnboardingOption = z.strictObject({
  id: z.string(),
  title: z.string(),
  emoji: z.string().nullable(),
  roleIds: z.array(z.string()),
  channelIds: z.array(z.string()),
});
export const OnboardingPrompt = z.strictObject({
  id: z.string(),
  title: z.string(),
  /** Pick at most one option. */
  single: z.boolean(),
  /** Pick at least one option. */
  required: z.boolean(),
  options: z.array(OnboardingOption),
});
export const OnboardingSettings = z.strictObject({ enabled: z.boolean(), prompts: z.array(OnboardingPrompt) });

export const GuideItemBody = z.object({
  /** Kept when it's 1–32 of A-Z, a-z, 0-9, _ and -; a new id otherwise. */
  id: z.string().max(32).optional(),
  title: z.string().trim().min(1).max(GUIDE_LIMITS.title),
  channelId: z.string().min(1).max(64),
});
export type GuideItemBody = z.infer<typeof GuideItemBody>;
/** null (or nothing in it) removes the guide. */
export const GuideBody = z
  .strictObject({
    welcome: z.string().trim().max(GUIDE_LIMITS.welcome).nullable(),
    tasks: z.array(GuideItemBody).max(GUIDE_LIMITS.items),
    resources: z.array(GuideItemBody).max(GUIDE_LIMITS.items),
  })
  .nullable();

const tag = "onboarding";

export const onboardingRoutes = [
  route({
    method: "GET",
    url: "/v1/orgs/:orgId/onboarding",
    tag,
    summary: "The community's onboarding questions, and the viewer's answers (null before answering).",
    auth: true,
    params: OrgParamsNonEmpty,
    response: OnboardingSettings.extend({ answered: z.array(z.string()).nullable(), answeredAt: zTime.nullable() }),
    errors: ["not_found"],
  }),
  route({
    method: "PUT",
    url: "/v1/orgs/:orgId/onboarding",
    tag,
    summary: "Set the onboarding questions and turn them on or off; every role and channel is checked.",
    auth: true,
    params: OrgParamsNonEmpty,
    body: OnboardingBody,
    response: OnboardingSettings,
    errors: ["not_found", "forbidden", "role_not_givable", "channel_not_public"],
  }),
  route({
    method: "POST",
    url: "/v1/orgs/:orgId/onboarding/answers",
    tag,
    summary: "Answer the onboarding questions: roles given and public channels opened, and the member's permissions now.",
    auth: true,
    params: OrgParamsNonEmpty,
    body: OnboardingAnswersBody,
    response: z.strictObject({ roleIds: z.array(z.string()), channelIds: z.array(z.string()), permissions: z.array(z.string()) }),
    errors: ["not_found", "forbidden", "onboarding_off", "answer_required", "one_answer"],
  }),
  route({
    method: "PUT",
    url: "/v1/orgs/:orgId/guide",
    tag,
    summary: "Set the community guide (a welcome line, up to five tasks and five resources, each opening a channel), or remove it.",
    auth: true,
    params: OrgParamsNonEmpty,
    body: GuideBody,
    response: z.strictObject({ guide: Guide.nullable() }),
    errors: ["not_found", "forbidden", "channel_not_found"],
  }),
];
