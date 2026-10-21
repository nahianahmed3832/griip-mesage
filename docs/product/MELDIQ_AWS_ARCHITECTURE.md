# Meldiq on AWS: current state, target architecture, costs (2026-10-21)

Master prompt §7. The approach is AWS-first, but each service must earn its place. Nothing in this file has been
created: every new resource below needs the owner's go-ahead. The existing infrastructure-as-code is in
`deploy/aws/` (CloudFormation).

## 1. Current state, checked read-only on 2026-10-21

Read through the AWS tools of this session: `sts:GetCallerIdentity` and S3 `Get*` calls only, with no changes.

| Item | Found |
|---|---|
| Account | 914016532802 |
| **Credentials used by the AWS tools** | **the account's root user** (profile `griipbuddy`). Already flagged on 2026-10-17 (`docs/ORBIT_AWS_PRODUCTION_2026-10-17.md` §7). Still open: switch the tools to `orbit-admin` or a new administrator, then deactivate the root access key (owner's steps) |
| Media bucket `griipbuddy-orbit-media-sg` | ap-southeast-1. SSE-S3 (AES256), SSE-C blocked. All four public-access blocks on. Versioning on. Lifecycle aborts incomplete multipart uploads after 1 day and expires noncurrent versions after 30 days. **No CORS rules, by design**: Meldiq Web reaches files through the API's nginx (`/media/…`, web 0.2.4-beta) |
| Test bucket `griipbuddy-orbit-media-sg-test` | Same settings |
| Other buckets | `griipbuddy1` (CORS for griipbuddy.com) and `gadgetglitz`: not Meldiq's, not touched |
| API, LiveKit, ntfy | Hostinger VPS 72.60.107.88 (Ubuntu 24.04, 2 vCPU, 8 GB), Docker Compose (`docs/DEPLOYMENT.md`) |
| Database | SQLite (WAL) on the VPS volume; MongoDB not provisioned (`MELDIQ_MONGODB_MIGRATION.md`) |
| Email | SES stack (`deploy/aws/orbit-mail.yaml`, `docs/ORBIT_SES_SETUP_2026-10-18.md`) |
| IaC already in Git | `orbit-storage.yaml` (bucket, runtime user with boundary), `orbit-backups.yaml`, `orbit-mail.yaml`, `orbit-ops-admin.yaml` |

## 2. Target architecture (phased)

```
Phones, browsers ──HTTPS/WSS──▶ CloudFront (web app, static) ─┐
                 ──HTTPS/WSS──▶ ALB (API, WebSocket)  ──▶ ECS Fargate: api (1–N tasks)
                                                              │   ├─▶ MongoDB Atlas on AWS (ap-southeast-1, PrivateLink)
                                                              │   ├─▶ S3 media (encrypted objects, presigned multipart)
                                                              │   ├─▶ SQS: jobs (push fan-out, retention, AI tasks) + DLQ
                                                              │   ├─▶ SES (verification, reset, recovery, invites)
                                                              │   └─▶ Secrets Manager (DB URI, LiveKit keys, FCM)
                 ──UDP/TCP (WebRTC)───────────────────▶ EC2: LiveKit (public IP, UDP ports), TURN/TLS on 443
EventBridge Scheduler ──▶ SQS (retention, usage snapshots)        CloudWatch logs/metrics/alarms · CloudTrail · Budgets
```

| Need | Service | Why this one | Phase |
|---|---|---|---|
| File bytes | **S3** (exists) | Already in production. Clients upload with presigned multipart URLs (1 GiB target) and never hold AWS credentials | done |
| Canonical database | **MongoDB Atlas on AWS** | Master prompt §6. Atlas keeps MongoDB's semantics (see the DocumentDB note in the migration doc) | P1, owner creates it |
| API compute | **ECS on Fargate** behind an **ALB** | No servers to patch. The ALB supports WebSockets and health checks. One task now, more once the WebSocket hub is shared (next row). EC2 would be cheaper but needs patching | P2 |
| Realtime fan-out with more than one API task | Redis-compatible pub/sub (**ElastiCache Serverless**) or MongoDB change streams | The hub is in memory in one process today. With two or more tasks, events must cross tasks. Change streams avoid a new service | P2, measure first |
| Media (calls) | **EC2** (c7g.large or c6i.large) running LiveKit, with an Elastic IP; UDP 50000–60000 and TURN/TLS 443 | Latency-sensitive UDP can't go through an HTTP load balancer. Fargate's networking limits make self-hosted LiveKit awkward there. LiveKit Cloud is the managed alternative, chosen on measured quality and cost | P2 |
| Static web app | **S3 + CloudFront** (origin access control) | Cheap, cached, TLS. The web's `/media` proxy then moves to the ALB path or to signed CloudFront URLs | P2 |
| Background jobs | **SQS** (with a DLQ) plus **EventBridge Scheduler** | Retention and push fan-out run inside the API today. Queues give retries and idempotency once there are several tasks | P2 |
| Secrets | **Secrets Manager** | Replaces `.env` on the VPS. Rotation for the S3 runtime key (or an IAM task role, so no key at all on ECS) | P2 |
| Keys | **KMS** | SSE-KMS on the media bucket (today SSE-S3). The objects are already end-to-end encrypted, so this is defence in depth and an audit trail | P3 |
| Email | **SES** (exists) | Production access and DKIM per `ORBIT_SES_SETUP` | done or check |
| Logs, metrics, alarms | **CloudWatch**: logs from ECS, alarms on 5xx, latency, task restarts, LiveKit CPU and packet loss, queue age and DLQ depth | Needed to run more than one process | P2 |
| Audit | **CloudTrail** (management events, one trail, 90-day lookup is free) | Who changed what in the account | P1, cheap |
| Edge protection | **AWS WAF** on the ALB and CloudFront: rate rules for `/v2/auth/*` and `/v1/invites/*`, managed common rules | The API already rate-limits in process. WAF stops floods before they reach it | P3 |
| AI | **Amazon Bedrock**, only for features that send data the user chose to send (`MELDIQ_AI_ARCHITECTURE.md`) | No training on customer data; region availability is to be checked per model | P3 |
| Costs | **AWS Budgets** (an `orbit-monthly-10usd` budget exists) and **Cost Anomaly Detection** | Media egress and compute are the drivers | now |

Push notifications stay on FCM (Android) and the existing ntfy path. Neither moves to AWS.

## 3. Infrastructure as code

Templates are added next to the existing ones in `deploy/aws/` (CloudFormation, as already used) and deployed by
`orbit-ops-admin`, never by root. Planned templates:
- `orbit-network.yaml`: VPC with two public subnets and two private ones. No NAT gateway: S3 and Secrets Manager are
  reached through VPC endpoints, and Atlas through PrivateLink.
- `orbit-api.yaml`: ECS cluster, task definition with a task role (S3 under `orbit/`, SQS, SES send, read of its
  secrets), service, ALB, target group with `/healthz`, log group.
- `orbit-media.yaml`: LiveKit EC2 instance with a launch template, security group (UDP range, 7881/tcp, 443), Elastic
  IP and instance role.
- `orbit-jobs.yaml`: SQS queues and DLQs, Scheduler rules.
- `orbit-observability.yaml`: alarms and an SNS topic to the owner's email.

Validation: `cfn-lint` and `aws cloudformation validate-template` (read-only), then a change set reviewed before
execution. These templates aren't written yet. The order follows §5.

## 4. Cost estimates

**Assumptions:** ap-southeast-1 on-demand list prices as published in 2026. These are approximate and must be
re-checked in the AWS Pricing Calculator before approval. Egress is the dominant variable.

| Scenario | Main parts | Estimate per month |
|---|---|---|
| **Private beta** (≤ 50 people, today's VPS kept) | S3 ~50 GB with light egress; Atlas M10; CloudTrail; Budgets | S3 ~$3, **Atlas M10 ~$60–80**, total **~$65–90** (plus the existing VPS) |
| **Beta on AWS** (≤ 500 people) | 1 Fargate task (1 vCPU, 2 GB) ~$45; ALB ~$20–25; LiveKit EC2 c7g.large ~$55 plus egress; Atlas M10; S3 200 GB plus 300 GB egress ~$40; CloudWatch ~$10 | **~$250–300** |
| **Growing community** (5,000 people) | 2–3 tasks; Atlas M30 (~$400); LiveKit 2 instances; 2 TB media egress (~$240 S3/CloudFront); WAF ~$10 plus requests | **~$1,200–1,600** |
| **High-concurrency voice and video** | Media egress is about 0.5–1.5 GB per participant-hour of video. 1,000 participant-hours a day of video ≈ 30 TB a month ≈ **$3,000+** of egress alone | Compare with LiveKit Cloud pricing before scaling; prefer audio-first and simulcast layers |
| **AI** | Per-token Bedrock pricing; a summary of ~2,000 tokens in and 300 out on a small model costs fractions of a cent | Metered per community with quotas (AI doc) |

**Cost controls:**
- budgets at $100, $300 and $1,000 with alerts;
- a CloudWatch alarm on media egress;
- S3 lifecycle (exists);
- no NAT gateway;
- right-size Atlas after a month of metrics.

## 5. Order of work and what needs the owner

1. **Owner:** move the AWS tools off the root key (§1). Then enable CloudTrail. This is free and needs no app change.
2. **Owner:** create the Atlas cluster (migration doc §7).
3. Write and validate `orbit-network`, `orbit-api` and `orbit-observability` against a staging stack name. The owner
   approves the change set. Build an image of the API in ECR, run it on Fargate against Atlas, and rehearse with the
   staging apps (`-Pmeldiq.apiBaseUrl.development`).
4. Move LiveKit to EC2 after measuring call quality from Bangladesh and Singapore on both hosts.
5. Cut DNS over (`msg`, `rtc`) only with the owner, keeping the VPS as the rollback for a week.
