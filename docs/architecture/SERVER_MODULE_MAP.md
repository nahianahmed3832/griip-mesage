# Server module map (Architecture 1.0, step B1/B2, 2026-10-22)

Where each `server/src` file moved when the server took the module layout of
[`ARCHITECTURE_1_0_PLAN.md`](ARCHITECTURE_1_0_PLAN.md) §3. The move changed no code except import paths and the
migrations folder's path in `infrastructure/database/db.ts`. Older documents still name the old paths; use this table
to find a file.

Unchanged entrypoints (operations scripts and the Dockerfile call their `dist/` copies): `src/index.ts`,
`src/admin.ts`, `src/seed.ts`, `src/core/index.ts`.

Dependency rules and their current exceptions are checked by `server/test/architecture_boundaries.test.ts`
(`architecture_boundaries.allowlist.json`: imports; `architecture_tables.allowlist.json`: a module reading or writing
another module's tables). Both lists may only shrink.

## app

| Before | Now |
|---|---|
| `abuse.ts` | `app/middleware/abuse.ts` |
| `app.ts` | `app/server.ts` |
| `config.ts` | `app/config.ts` |
| `context.ts` | `app/context.ts` |

## shared

| Before | Now |
|---|---|
| `crypto.ts` | `shared/utilities/crypto.ts` |
| `util.ts` | `shared/utilities/util.ts` |

## infrastructure/database

| Before | Now |
|---|---|
| `db.ts` | `infrastructure/database/db.ts` |
| `mongo/admin.ts` | `infrastructure/database/mongo/admin.ts` |
| `mongo/catalog.ts` | `infrastructure/database/mongo/catalog.ts` |
| `mongo/client.ts` | `infrastructure/database/mongo/client.ts` |
| `mongo/config.ts` | `infrastructure/database/mongo/config.ts` |
| `mongo/index.ts` | `infrastructure/database/mongo/index.ts` |
| `mongo/migrate.ts` | `infrastructure/database/mongo/migrate.ts` |

## infrastructure/storage

| Before | Now |
|---|---|
| `storage/blobstore.ts` | `infrastructure/storage/blobstore.ts` |
| `storage/fallback.ts` | `infrastructure/storage/fallback.ts` |
| `storage/index.ts` | `infrastructure/storage/index.ts` |
| `storage/s3.ts` | `infrastructure/storage/s3.ts` |

## infrastructure/mail

| Before | Now |
|---|---|
| `accounts/mailLogo.ts` | `infrastructure/mail/mailLogo.ts` |
| `accounts/mailTemplate.ts` | `infrastructure/mail/mailTemplate.ts` |
| `accounts/mailer.ts` | `infrastructure/mail/mailer.ts` |

## modules/accounts

| Before | Now |
|---|---|
| `accounts/config.ts` | `modules/accounts/config.ts` |
| `accounts/deletion.ts` | `modules/accounts/deletion.ts` |
| `accounts/identity.ts` | `modules/accounts/identity.ts` |
| `accounts/index.ts` | `modules/accounts/index.ts` |
| `accounts/keyBackup.ts` | `modules/accounts/keyBackup.ts` |
| `accounts/keyBackupStore.ts` | `modules/accounts/keyBackupStore.ts` |
| `accounts/legacy.ts` | `modules/accounts/legacy.ts` |
| `accounts/passwords.ts` | `modules/accounts/passwords.ts` |
| `accounts/recovery.ts` | `modules/accounts/recovery.ts` |
| `accounts/recoveryStore.ts` | `modules/accounts/recoveryStore.ts` |
| `accounts/routes.ts` | `modules/accounts/routes.ts` |
| `accounts/schemas.ts` | `modules/accounts/schemas.ts` |
| `accounts/service.ts` | `modules/accounts/service.ts` |
| `accounts/sessions.ts` | `modules/accounts/sessions.ts` |
| `accounts/store.ts` | `modules/accounts/store.ts` |
| `accounts/workspaces.ts` | `modules/accounts/workspaces.ts` |
| `mongo/accounts.ts` | `modules/accounts/mongoStore.ts` |
| `mongo/keyBackups.ts` | `modules/accounts/mongoKeyBackupStore.ts` |
| `mongo/preferences.ts` | `modules/accounts/preferences/mongoStore.ts` |
| `mongo/recovery.ts` | `modules/accounts/mongoRecoveryStore.ts` |
| `mongo/relationships.ts` | `modules/accounts/relationships/mongoStore.ts` |
| `photos.ts` | `modules/accounts/photos.ts` |
| `preferences/routes.ts` | `modules/accounts/preferences/routes.ts` |
| `preferences/schema.ts` | `modules/accounts/preferences/schema.ts` |
| `preferences/store.ts` | `modules/accounts/preferences/store.ts` |
| `profile.ts` | `modules/accounts/profile.ts` |
| `relationships/rules.ts` | `modules/accounts/relationships/rules.ts` |
| `relationships/store.ts` | `modules/accounts/relationships/store.ts` |
| `reminders/routes.ts` | `modules/accounts/reminders/routes.ts` |
| `reminders/schedule.ts` | `modules/accounts/reminders/schedule.ts` |
| `reminders/service.ts` | `modules/accounts/reminders/service.ts` |
| `routes/connections.ts` | `modules/accounts/relationships/routes.ts` |
| `routes/users.ts` | `modules/accounts/usersRoutes.ts` |

## modules/administration

| Before | Now |
|---|---|
| `backup.ts` | `modules/administration/backup.ts` |
| `entitlements.ts` | `modules/administration/entitlements.ts` |
| `experiments/assign.ts` | `modules/administration/experiments/assign.ts` |
| `experiments/definitions.ts` | `modules/administration/experiments/definitions.ts` |
| `experiments/events.ts` | `modules/administration/experiments/events.ts` |
| `experiments/routes.ts` | `modules/administration/experiments/routes.ts` |
| `jobs.ts` | `modules/administration/jobs.ts` |
| `ops/connectors.ts` | `modules/administration/ops/connectors.ts` |
| `ops/health.ts` | `modules/administration/ops/health.ts` |
| `ops/metrics.ts` | `modules/administration/ops/metrics.ts` |
| `ops/routes.ts` | `modules/administration/ops/routes.ts` |
| `retiredData.ts` | `modules/administration/retiredData.ts` |
| `usage/meter.ts` | `modules/administration/usage/meter.ts` |

## modules/auth

| Before | Now |
|---|---|
| `routes/auth.ts` | `modules/auth/routes.ts` |

## modules/calls

| Before | Now |
|---|---|
| `livekit.ts` | `modules/calls/livekit.ts` |
| `routes/calls.ts` | `modules/calls/routes.ts` |
| `routes/meetings.ts` | `modules/calls/meetingsRoutes.ts` |
| `workspace/meetings.ts` | `modules/calls/meetings.ts` |

## modules/federation

| Before | Now |
|---|---|
| `core/app.ts` | `modules/federation/core/app.ts` |
| `core/node.ts` | `modules/federation/core/node.ts` |
| `federation/identity.ts` | `modules/federation/identity.ts` |
| `federation/records.ts` | `modules/federation/records.ts` |
| `federation/resolver.ts` | `modules/federation/resolver.ts` |
| `federation/routes.ts` | `modules/federation/routes.ts` |
| `federation/service.ts` | `modules/federation/service.ts` |
| `federation/sign.ts` | `modules/federation/sign.ts` |
| `federation/transport.ts` | `modules/federation/transport.ts` |

## modules/files

| Before | Now |
|---|---|
| `routes/attachments.ts` | `modules/files/routes.ts` |

## modules/messaging

| Before | Now |
|---|---|
| `channelRepair.ts` | `modules/messaging/channelRepair.ts` |
| `history/store.ts` | `modules/messaging/history/store.ts` |
| `mentions.ts` | `modules/messaging/mentions.ts` |
| `mongo/history.ts` | `modules/messaging/history/mongoStore.ts` |
| `routes/conversations.ts` | `modules/messaging/conversationsRoutes.ts` |
| `routes/history.ts` | `modules/messaging/history/routes.ts` |
| `routes/messages.ts` | `modules/messaging/messagesRoutes.ts` |
| `routes/search.ts` | `modules/messaging/search/routes.ts` |
| `search/service.ts` | `modules/messaging/search/service.ts` |

## modules/notifications

| Before | Now |
|---|---|
| `push/address.ts` | `modules/notifications/address.ts` |
| `push/fcm.ts` | `modules/notifications/fcm.ts` |
| `push/service.ts` | `modules/notifications/service.ts` |
| `push/transport.ts` | `modules/notifications/transport.ts` |
| `routes/push.ts` | `modules/notifications/routes.ts` |

## modules/organizations

| Before | Now |
|---|---|
| `authz.ts` | `modules/organizations/authz.ts` |
| `communityMigration.ts` | `modules/organizations/communityMigration.ts` |
| `invites.ts` | `modules/organizations/invites.ts` |
| `mongo/reports.ts` | `modules/organizations/reports/mongoStore.ts` |
| `orgRepair.ts` | `modules/organizations/orgRepair.ts` |
| `permissions/catalog.ts` | `modules/organizations/permissions/catalog.ts` |
| `permissions/channel.ts` | `modules/organizations/permissions/channel.ts` |
| `permissions/rules.ts` | `modules/organizations/permissions/rules.ts` |
| `reports/seal.ts` | `modules/organizations/reports/seal.ts` |
| `reports/store.ts` | `modules/organizations/reports/store.ts` |
| `roles.ts` | `modules/organizations/roleRules.ts` |
| `routes/applications.ts` | `modules/organizations/applicationsRoutes.ts` |
| `routes/community.ts` | `modules/organizations/communityRoutes.ts` |
| `routes/expressions.ts` | `modules/organizations/expressionsRoutes.ts` |
| `routes/guide.ts` | `modules/organizations/guideRoutes.ts` |
| `routes/onboarding.ts` | `modules/organizations/onboardingRoutes.ts` |
| `routes/orgs.ts` | `modules/organizations/orgsRoutes.ts` |
| `routes/reports.ts` | `modules/organizations/reports/routes.ts` |
| `routes/roles.ts` | `modules/organizations/rolesRoutes.ts` |
| `workspace/activityAlerts.ts` | `modules/organizations/activityAlerts.ts` |
| `workspace/communityStart.ts` | `modules/organizations/communityStart.ts` |
| `workspace/eventRepeat.ts` | `modules/organizations/eventRepeat.ts` |

## modules/realtime

| Before | Now |
|---|---|
| `hub.ts` | `modules/realtime/hub.ts` |
| `ws.ts` | `modules/realtime/ws.ts` |
