# People & HR in Meldiq: integration plan (round 6, 2026-10-19)

Status: **design only.** The owner chose "HR: design first". Nothing in the HR system, its server or its data was
changed for this document. Every step below that touches the HR system or production needs the owner's approval first.

## 1. What exists today (read-only audit)

The employee-management website is its own product, separate from Meldiq:

| | |
|---|---|
| Code | `/Volumes/code_files/griipbuddy_employee_management`, private GitHub repo `nahianahmed3832/griipbuddy-employee-management` |
| Stack | Next.js 16.4 (App Router), React 19, Prisma 7 on PostgreSQL 18, Auth.js 5 (credentials only), argon2id, zod |
| Live | `https://griipbuddy.cloud`, on the same Hostinger VPS as Meldiq (72.60.107.88), Compose project `griipbuddy-employee`, app on `127.0.0.1:3100`, PostgreSQL unpublished, own named volumes, own nginx route |
| Data | 32 production tables (users, employees, employment periods, departments, positions, schedules and slots, attendance sessions and corrections, overtime reviews, calendar days, leave requests, performance reviews, disciplinary records, assets, notifications, compensation rates, salary periods/calculations/adjustments, pre-orders and commissions, tasks, weekly earnings, shift swaps, documents, audit log) |
| Roles | `ADMIN`, `HR_MANAGER`, `EMPLOYEE`, plus the separate `can_manage_commissions` grant. Every protected operation re-reads role, status, ownership and session version from PostgreSQL |
| Sessions | 12-hour absolute lifetime, session-version invalidation, lockout after 5 wrong passwords, Origin checks on mutations |
| Jobs | a daily `notifications:due` command (18:10 UTC) with an advisory lock and deduplication |
| Organisation | single-tenant: one `organization_settings` row (BDT, Asia/Dhaka, Monday workweek, 48-hour overtime threshold) |
| Open work | a schedule-conflict fix is implemented and tested locally but **not deployed** (its report waits for a designated production test record) |

What it does well and must keep doing: attendance and overtime rules, leave and approvals, salary and commission
calculation with historical snapshots, private documents, and a strict audit trail. None of that should be rewritten
inside Meldiq.

## 2. Options considered

| Option | Verdict |
|---|---|
| Merge the HR code and database into Meldiq's server | **No.** Different stack (Next.js/Prisma/PostgreSQL vs Fastify/SQLite), payroll and audit invariants enforced by PostgreSQL constraints and locks, and real employee and salary data. High risk, no benefit to people using either. |
| Copy HR data into Meldiq | **No.** Two systems of record for the same facts; payroll and private documents would sit next to end-to-end encrypted messaging with weaker controls. |
| Embed the HR website in a Meldiq web view | Possible for a quick start, but two sign-ins and no notifications or reminders in Meldiq; a stopgap at most. |
| **Keep the HR system as the system of record, connect it to Meldiq with per-person consent** | **Recommended.** Meldiq already has the connector framework for this (OAuth with PKCE, encrypted tokens, per-workspace install, link previews, notifications in channels, reminders). The HR system keeps enforcing its own roles on every request. |

## 3. Recommended design

### 3.1 The HR system becomes an OAuth 2.0 provider for itself

Add to the HR app (new code there, reviewed and tested on its own):

- `GET /oauth/authorize` (signed-in HR user consents; PKCE S256 required; only registered redirect URIs) and
  `POST /oauth/token` (authorization code and refresh grants; client authentication for Meldiq's server; refresh token
  rotation; revocation at `POST /oauth/revoke`).
- Scopes, narrow and read-mostly:

| Scope | Gives | For |
|---|---|---|
| `hr.profile.self` | the person's own safe profile DTO (name, employee number, department, position, manager) | linking accounts |
| `hr.directory.read` | the staff directory DTO already used by the app (no bank details, no documents, no pay) | "Message a colleague" from a profile |
| `hr.schedule.self` / `hr.schedule.team` | own shifts; team shifts for managers (role checked by HR) | Meldiq calendar and reminders |
| `hr.leave.self` | own leave balances and requests; `hr.leave.request` to file one | Meldiq leave card |
| `hr.leave.approve` | the approval queue for HR/Admin (role checked by HR on every call) | reminders to approvers |
| `hr.notifications.read` | the person's HR notifications (titles already exclude amounts and private notes) | Meldiq notification center |

  **Never offered through Meldiq:** salary, compensation rates, commissions, bank details, private documents,
  disciplinary records, performance reviews, audit history. Those stay in the HR website only.
- Every token is bound to the HR user who consented. The HR APIs keep their existing authorisation code paths: a token
  never widens what that user's role allows, and role changes or disabling invalidate tokens (session version).

### 3.2 Meldiq side: a "People & HR" connector

- A connector `people-hr` in `server/src/integrations` like the others: the workspace admin installs it and enters the
  HR system's address (https only); each member connects their own HR account (consent screen on the HR site).
- Account linking is by consent only, never by matching email addresses silently.
- Meldiq screens: a People tab in the workspace (directory with Message buttons), "My shifts" and "My leave" cards on
  Home, an approvals card for HR/Admin. All data is fetched live with the member's token and shown only to them; it is
  never stored in Meldiq's database, never put in channels, and never in push payloads.
- Reminders: shift starts and leave decisions become Meldiq reminders (server-fired, `docs/REMINDERS.md`), with content
  sealed on the phone like every reminder. Approvers get "N leave requests waiting" reminders, without names or reasons
  in the notification.
- Notifications: the HR app sends signed webhooks (HMAC, timestamp, replay window) for "leave decided", "shift changed",
  "approval waiting"; Meldiq turns them into personal notifications for the linked member only.

### 3.3 Workspace mapping

The HR system is single-tenant today. One Meldiq workspace links to one HR installation (its base URL). Several Meldiq
workspaces would need several HR installations, or multi-tenant work in the HR app, which is out of scope here.

## 4. Phases, each with its own approval

| Phase | Work | Where | Approval needed |
|---|---|---|---|
| 0 | Deploy the pending HR schedule fix, if wanted (its own report) | HR repo + HR production | owner, separately |
| 1 | OAuth provider in the HR app + scope-limited read APIs + tests (unit, PostgreSQL integration, HTTP suite) | HR repo, local and disposable PostgreSQL only | owner reviews the design and the diff |
| 2 | Rehearsal on a restored **copy** of production (the existing encrypted backup restored into a disposable Compose project, as the HR release reports already do); no real records changed | VPS, separate project | owner (uses the backup) |
| 3 | `people-hr` connector in Meldiq, mock-tested like the other connectors; Android People tab, shift and leave cards | Meldiq repo | owner (push + Meldiq deploy) |
| 4 | HR production release of phase 1 with backup and rollback (data-preserving, application-only, like `PRODUCTION_UI_RELEASE.md`) | HR production | owner |
| 5 | Pilot with one or two consenting staff accounts; then everyone | both | owner |
| 6 | HR webhooks for notifications and reminders | both | owner |

Rollback at every phase: the HR website keeps working on its own; uninstalling the connector in Meldiq revokes tokens
and removes nothing from the HR system.

## 5. Security and privacy rules

- The HR system remains the only place that decides who may see what. Meldiq never caches HR records.
- Payroll, compensation, private documents, discipline and performance never leave the HR website.
- Tokens are stored encrypted (`INTEGRATIONS_TOKEN_KEY`), refreshed and revoked like other connectors.
- Audit: the HR app records each OAuth consent, token issue and revocation in its own audit log; Meldiq records
  installs and connections in the workspace audit log.
- No statutory payroll or employment rules are invented in Meldiq; leave types and balances come from the HR system.
  Any change to leave or overtime rules needs review for the organisation's jurisdiction (Bangladesh labour law today).

## 6. Questions for the owner before phase 1

1. Should HR managers approve leave from inside Meldiq, or only see the queue there and approve on the HR website?
2. Which Meldiq workspace links to `griipbuddy.cloud`?
3. Is the pending HR schedule fix (phase 0) to be deployed first?
