# Orbit account emails on Amazon SES: setup (2026-10-18)

**Status: AWS and DNS deployed on 2026-10-18, with the owner's approval (§8).** What's left:

- production access, which the owner requests in the console (step 6);
- the access key and the `.env` settings, which come with the API 0.4.0 deploy (steps 7–8).

Until then the server sends nothing through SES.

Orbit 2.0 sends three kinds of account email (`server/src/accounts/mailer.ts`), all plain text without links:

- verification codes;
- password-reset codes;
- the "someone tried to sign up with your address" notice.

Until SES is set up, production keeps `MAIL_TRANSPORT=none`. v2 sign-up and password reset then answer 503, and apps
before 2.0 aren't affected.

**Files:**

- `deploy/aws/orbit-mail.yaml` is new: the SES stack.
- `deploy/aws/orbit-ops-admin.yaml` is changed. Its permissions boundary and the operator user both gain SES (§3).

## 1. What the stack creates (Singapore, `ap-southeast-1`)

| Resource | What it is |
|---|---|
| SES domain identity **`griipbuddy.cloud`** (`MailDomain`) | Verified through three DKIM CNAME records (Easy DKIM, 2048-bit keys, rotated by SES). Custom MAIL FROM domain `bounce.griipbuddy.cloud`, so SPF aligns with the domain as well as DKIM; SES falls back to `amazonses.com` until its MX record exists. Kept if the stack is deleted (`DeletionPolicy: Retain`), because DNS points at its keys. |
| Configuration set **`orbit-mail-prod`** | The identity's default, so every send uses it although the server doesn't name one: TLS required to the recipient's mail server; reputation metrics; addresses that bounce or complain are suppressed; a sending switch for all of Orbit's email (§6). No open or click tracking. |
| IAM user **`orbit-api-mail-prod-ap-southeast-1`** | Only `ses:SendEmail`, only with the From address **exactly `no-reply@griipbuddy.cloud`**, only from the VPS's address **72.60.107.88**. It carries the boundary `orbit-runtime-boundary`. Its access key is never a stack output. |

**Design choices:**

- **An IAM user, not a role.** The VPS isn't in AWS, so a role would need IAM Roles Anywhere, as for storage
  (`docs/AWS_STORAGE_PLAN.md` §5). If the API ever runs on AWS, attach the same policy to its role and leave the SES key
  settings unset. The mailer then uses the default credential chain.
- **No `ses:SendRawEmail`.** The server only calls SES API v2 `SendEmail`. `SendRawEmail` is API v1 and SMTP, which it
  doesn't use.
- **The source-IP condition** makes a leaked key useless anywhere but the VPS. The S3 key's delete and list
  permissions already work the same way. On 2026-10-18 the SES endpoint `email.ap-southeast-1.amazonaws.com` had
  IPv4 addresses only, so requests arrive from the VPS's IPv4 address.
- **`SandboxTestRecipient`** (empty by default) adds one statement for a test send while the account is in the
  sandbox, where SES can also authorize the send against the verified recipient's own identity. Clear it after
  production access (step 9).

## 2. Cost (list prices: check before use)

- Outbound email: **US$0.10 per 1,000 emails**. Attachments: US$0.12 per GB, but Orbit sends none. Source:
  aws.amazon.com/ses/pricing, read 2026-10-18.
- No charge for the identity or the configuration set. Not used: Virtual Deliverability Manager and dedicated IPs,
  which cost extra.
- Estimate: an internal beta of about 50 people is a few hundred emails a month, **about US$0.02**. 500 people is
  about 2,000 a month, about US$0.20. New-account Free Tier credits may cover it.
- Budget `orbit-monthly-10usd` already covers the whole account.

**To be checked by the owner:** the price on the pricing page, in `ap-southeast-1`, on the day of setup.

## 3. Why `orbit-ops-admin.yaml` changes too

- **The boundary only allowed S3.** A permissions boundary caps what a user may do, so the mail user would have been
  refused every send. The new statement `OrbitMailSending` allows `ses:SendEmail` on this account's SES identities and
  on `orbit-*` configuration sets. The mail user's own policy narrows that to one address, one configuration set and
  one IP. The S3 users gain nothing, because their own policies don't mention SES.
- **`orbit-ops-admin` had no SES permissions** and could only manage `orbit-storage-*` stacks. It now also gets:
  - `orbit-mail-*` stacks;
  - the SES identity and configuration-set actions CloudFormation needs (from the resource types' handler
    permissions);
  - SES account and listing read-outs.

  It still can't send email.
- **Both managed policies keep their first `Description`.** Changing a managed policy's description replaces the
  policy, and replacing a named policy fails.
- **Who can update it.** The `orbit-ops-admin` stack was created by the root user, to bootstrap
  (`docs/ORBIT_AWS_PRODUCTION_2026-10-17.md` §1), and `orbit-ops-admin` can't change its own policy. So this one update
  needs an administrator. That's either the general administrator from that report's §7, if it exists by then, or the
  root user once more.

## 4. Operator steps, in order

The commands run from the repository root on the operator's Mac, with the AWS CLI at `~/.local/bin/aws`. Each one
needs the owner's approval first.

**Step 1. Update `orbit-ops-admin` (as an administrator, not `orbit-admin`).** `<administrator-profile>` is the
general administrator's profile, or the root profile `griipbuddy` if none exists yet; that choice is the owner's.
Review the change set before running it. Expect exactly two changes, both `Modify` with `Replacement: False`: `RuntimeBoundary` and `OpsAdminPolicy`.

```bash
aws cloudformation deploy --region ap-southeast-1 --profile <administrator-profile> --stack-name orbit-ops-admin --template-file deploy/aws/orbit-ops-admin.yaml --capabilities CAPABILITY_NAMED_IAM --no-execute-changeset
```

```bash
aws cloudformation describe-change-set --region ap-southeast-1 --profile <administrator-profile> --stack-name orbit-ops-admin --change-set-name <name printed above> --query 'Changes[].ResourceChange.[Action,LogicalResourceId,Replacement]' --output table
```

```bash
aws cloudformation execute-change-set --region ap-southeast-1 --profile <administrator-profile> --stack-name orbit-ops-admin --change-set-name <name printed above>
```

**Step 2. Create the mail stack (as `orbit-admin`).** The boundary ARN is built from the account ID, so no account ID
goes into the repository. `orbit-admin` can't read the `orbit-ops-admin` stack itself.

```bash
BOUNDARY="arn:aws:iam::$(aws sts get-caller-identity --profile orbit-admin --query Account --output text):policy/orbit-runtime-boundary"
```

```bash
aws cloudformation deploy --region ap-southeast-1 --profile orbit-admin --stack-name orbit-mail-prod --template-file deploy/aws/orbit-mail.yaml --capabilities CAPABILITY_NAMED_IAM --parameter-overrides Environment=prod MailDomain=griipbuddy.cloud PermissionsBoundaryArn="$BOUNDARY" --tags app=orbit env=prod
```

The defaults are:

- `SenderLocalPart=no-reply`;
- `MailFromSubdomain=bounce`;
- `ServerIp=72.60.107.88/32`;
- `SandboxTestRecipient` empty.

**Step 3. Read the DNS records.**

```bash
aws cloudformation describe-stacks --region ap-southeast-1 --profile orbit-admin --stack-name orbit-mail-prod --query 'Stacks[0].Outputs[].[OutputKey,OutputValue]' --output table
```

**Step 4. Add the DNS records in Hostinger.** The zone is at Hostinger (name servers `*.dns-parking.com`): hPanel ›
Domains › griipbuddy.cloud › DNS records. Hostinger takes names relative to the zone, so drop the trailing
`.griipbuddy.cloud` from each name. On 2026-10-18 the zone had no MX, SPF or DMARC records, so nothing existing
conflicts.

| From output | Type | Name in Hostinger | Value |
|---|---|---|---|
| `DkimRecord1` | CNAME | `<token1>._domainkey` | `<token1>.dkim.amazonses.com` |
| `DkimRecord2` | CNAME | `<token2>._domainkey` | `<token2>.dkim.amazonses.com` |
| `DkimRecord3` | CNAME | `<token3>._domainkey` | `<token3>.dkim.amazonses.com` |
| `MailFromMxRecord` | MX | `bounce` | `feedback-smtp.ap-southeast-1.amazonses.com`, priority 10 |
| `MailFromSpfRecord` | TXT | `bounce` | `v=spf1 include:amazonses.com ~all` |
| (recommended, not an output) | TXT | `_dmarc` | `v=DMARC1; p=none` |

DMARC starts with `p=none`, which only reports. Move to `p=quarantine` after a few weeks without failures. To receive
DMARC reports, add `rua=mailto:<an address that receives mail>`; `griipbuddy.cloud` itself has no MX.

**Step 5. Wait for verification.** It's usually minutes to hours, and at most 72 hours, after which SES gives up and
the records must be checked. Expect `true`, `SUCCESS` and `SUCCESS`:

```bash
aws sesv2 get-email-identity --region ap-southeast-1 --profile orbit-admin --email-identity griipbuddy.cloud --query '{sending: VerifiedForSendingStatus, dkim: DkimAttributes.Status, mailFrom: MailFromAttributes.MailFromDomainStatus}'
```

**Step 6. Request production access.** In the sandbox, SES sends only to verified addresses, at most 200 emails a day
and one a second. Request it in the console as the root user or the administrator: SES › Account dashboard › Request
production access, in Asia Pacific (Singapore).

- Mail type: **Transactional**.
- Website URL: Orbit's or GriipBuddy's site.
- Use case, a draft: "Orbit is an end-to-end encrypted workplace messenger. SES sends only account emails that a
  person requests in the app: email-verification codes at sign-up, password-reset codes, and a notice when someone
  tries to register an address that already has an account. No marketing or bulk email, plain text without links
  or tracking. Volume is a few hundred emails a month during the internal beta. Requests are rate-limited per
  network and per account, so codes can't be sent in bulk. Bounces and complaints are handled by the SES suppression
  list, enabled on the configuration set for BOUNCE and COMPLAINT, so addresses that bounce or complain aren't sent
  to again. Sending is authenticated with DKIM, a custom MAIL FROM domain (SPF) and DMARC."

AWS usually answers within a day and may ask questions. Meanwhile, step 6a allows a test.

**Step 6a (optional). A sandbox test.** Verify one test address you own, then click the link SES emails to it:

```bash
aws sesv2 create-email-identity --region ap-southeast-1 --profile orbit-admin --email-identity <test address>
```

Then allow the test send:

```bash
aws cloudformation deploy --region ap-southeast-1 --profile orbit-admin --stack-name orbit-mail-prod --template-file deploy/aws/orbit-mail.yaml --capabilities CAPABILITY_NAMED_IAM --parameter-overrides SandboxTestRecipient=<test address> --no-fail-on-empty-changeset
```

`deploy` keeps the other parameters' previous values when they aren't given.

**Step 7. Create the access key and pipe it into the VPS `.env`.** This is the same way as the S3 key. The key is
never printed, never in chat and never in Git, and the old `.env` is kept (root-only) next to it.

```bash
aws iam create-access-key --profile orbit-admin --user-name orbit-api-mail-prod-ap-southeast-1 --query 'AccessKey.[AccessKeyId,SecretAccessKey]' --output text | ssh root@72.60.107.88 'umask 077; cd /opt/griip-internal-messaging && read -r id secret && cp .env ".env.pre-ses-$(date -u +%Y%m%dT%H%M%SZ)" && grep -v -e "^SES_ACCESS_KEY_ID=" -e "^SES_SECRET_ACCESS_KEY=" .env > .env.new && printf "SES_ACCESS_KEY_ID=%s\nSES_SECRET_ACCESS_KEY=%s\n" "$id" "$secret" >> .env.new && mv .env.new .env && echo "SES key written"'
```

**Step 8. Turn SES on (only with API 0.4.0 or later).** Earlier servers ignore these settings. The address in
`MAIL_FROM` must be exactly `no-reply@griipbuddy.cloud`, in lowercase, or SES refuses the send. The display name
before it is free. Set these in `/opt/griip-internal-messaging/.env`:

```
MAIL_TRANSPORT=ses
MAIL_FROM="Orbit <no-reply@griipbuddy.cloud>"
SES_REGION=ap-southeast-1
```

Then check the file and restart only `api`:

```bash
ssh root@72.60.107.88 'cd /opt/griip-internal-messaging && bash scripts/validate-env.sh && docker compose -p griip-internal-messaging -f compose.yaml up -d --no-deps api'
```

`validate-env.sh` should print `ok: account emails sent with Amazon SES from Orbit <no-reply@griipbuddy.cloud>`.

**Step 9. Check it works, then tidy up.**

1. Sign up in the Orbit 2.0 app with a real address. In the sandbox, that's the address from step 6a. The code
   should arrive, signed by `griipbuddy.cloud` ("mailed-by bounce.griipbuddy.cloud" in Gmail's details).
2. Check that nothing failed:
   `docker compose -p griip-internal-messaging -f compose.yaml logs api | grep "account email not sent"`
   should print nothing. A failure is logged with the SES error, never the code, and the app shows "We couldn't send
   the email just now".
3. After production access, clear the test recipient and remove its identity:

   ```bash
   aws cloudformation deploy --region ap-southeast-1 --profile orbit-admin --stack-name orbit-mail-prod --template-file deploy/aws/orbit-mail.yaml --capabilities CAPABILITY_NAMED_IAM --parameter-overrides SandboxTestRecipient= --no-fail-on-empty-changeset
   ```

   ```bash
   aws sesv2 delete-email-identity --region ap-southeast-1 --profile orbit-admin --email-identity <test address>
   ```

## 5. Rollback

- **Stop sending, keep everything:** set `MAIL_TRANSPORT=none` and restart `api`. v2 sign-up and reset answer 503
  again, and nothing else changes.
- **Stop the key:**
  `aws iam update-access-key --profile orbit-admin --user-name orbit-api-mail-prod-ap-southeast-1 --access-key-id <id> --status Inactive`.
- **Remove the stack:** `aws cloudformation delete-stack ... --stack-name orbit-mail-prod` removes the user and the
  configuration set (delete the user's access keys first). The domain identity stays, by design. Delete it with
  `aws sesv2 delete-email-identity` only when its DNS records are removed too.
- **Remove the DNS records:** delete the six records in §8 in hPanel, or with the Hostinger API's `dns_records_delete`
  filtered by name and type. Hostinger also keeps zone snapshots. No record that existed before was changed.
- **Undo the boundary change:** re-deploy `orbit-ops-admin.yaml` from before this change, as an administrator.
  That's `git show 6f355a1:deploy/aws/orbit-ops-admin.yaml`, which matched the deployed template (checked before
  step 1). If the mail stack is still there, its user then can't send any more.

## 6. Operations

- **Key rotation every 90 days**, as for S3:
  1. Create a second key the way step 7 does.
  2. Restart `api` and send a test code.
  3. Deactivate the old key, and delete it a week later.
- **Stop all of Orbit's email at once** (abuse, a bounce spike), without a deploy:

  ```bash
  aws sesv2 put-configuration-set-sending-options --region ap-southeast-1 --profile orbit-admin --configuration-set-name orbit-mail-prod --no-sending-enabled
  ```

  `--sending-enabled` turns it back on. It drifts from the stack until set back.
- **Reputation:** SES › Reputation metrics, or `aws sesv2 get-account --region ap-southeast-1 --profile orbit-admin`.
  SES puts an account under review at a bounce rate of about 5% or a complaint rate of about 0.1%, and may pause
  sending at about 10% or 0.5% (thresholds from the SES docs; check). Codes that people ask for should stay far below.
- **Changing the address or domain:** the From address is a stack parameter (`SenderLocalPart`, `MailDomain`) and an
  IAM condition. Change the stack first, then `MAIL_FROM`. A new `MailDomain` means new DKIM records.

## 7. Validation (2026-10-18, on this Mac)

| Check | `orbit-mail.yaml` | `orbit-ops-admin.yaml` |
|---|---|---|
| cfn-lint 1.57.2, `--regions ap-southeast-1 --include-checks I` | 0 errors, 0 warnings, 0 info | 0 errors, 0 warnings, 0 info |
| cfn-guard 3.2.1 with the AWS Guard Rules Registry 1.0.2 (`guard-rules-registry-all-rules.guard`) | PASS: 3 IAM rules apply and pass, 64 don't apply | PASS: 3 IAM rules apply and pass, 64 don't apply |
| CloudFormation `validate-template` (read-only, as `orbit-admin`) | valid; needs `CAPABILITY_NAMED_IAM` | valid; needs `CAPABILITY_NAMED_IAM` |

**Coverage:**

- The registry release has no SES rules. So the SES-specific choices were checked by reading them against the SES
  docs and the resource schemas, not by a rule set: TLS, suppression, the From-address and IP conditions, and no
  `SendRawEmail`.
- CloudFormation's account-aware pre-deployment validation ran with both real change sets in §8, and neither was
  stopped.

To run the checks again (cfn-lint through `uv`; cfn-guard from its GitHub release and the rules from
aws-guard-rules-registry's release 1.0.2):

```bash
uvx --from cfn-lint==1.57.2 cfn-lint --regions ap-southeast-1 --include-checks I -- deploy/aws/orbit-mail.yaml deploy/aws/orbit-ops-admin.yaml
```

```bash
cfn-guard validate --rules guard-rules-registry-all-rules.guard --data deploy/aws/orbit-mail.yaml --show-summary all
```

## 8. Deployment record (2026-10-18, owner-approved)

| Step | Done | By |
|---|---|---|
| 1. `orbit-ops-admin` update | The deployed template was saved first and matched `6f355a1`. Change set `orbit-ops-admin-ses-20261018` showed exactly `Modify OpsAdminPolicy` and `Modify RuntimeBoundary`, both `Replacement: False`. Executed; `UPDATE_COMPLETE` at 07:43 UTC. | root profile `griipbuddy`, **once**, for this change set only (approved by the owner) |
| 2. `orbit-mail-prod` | `CREATE_COMPLETE` at 07:45 UTC with `Environment=prod`, `MailDomain=griipbuddy.cloud`, the boundary and the defaults. Checked afterwards: the configuration set is TLS `REQUIRE`, reputation on, sending on, suppression `BOUNCE` and `COMPLAINT`, no tracking. The user carries `orbit-runtime-boundary` and one inline policy, `orbit-mail-send` (`ses:SendEmail`, From `no-reply@griipbuddy.cloud`, from `72.60.107.88/32`), and has **no access keys**. | `orbit-admin` |
| 4. DNS (Hostinger API, overwrite off, TTL 300) | `64ki4e56qz4fn4djtpxj5mintfh4ltf4._domainkey`, `pg6ogzqiicfxavfi4hid4yzsukiq6qra._domainkey` and `t4njpohjzimnbwb4kgtcsgleumzqiaet._domainkey` are CNAMEs to `<same>.dkim.amazonses.com`. `bounce` has MX `10 feedback-smtp.ap-southeast-1.amazonses.com` and TXT `v=spf1 include:amazonses.com ~all`; `_dmarc` has TXT `v=DMARC1; p=none`. All six were checked on `horizon.dns-parking.com`, and the existing A and CNAME records are unchanged. | Hostinger API |
| 5. Verification | At 07:53 UTC, about 4 minutes after the DNS records: `VerifiedForSendingStatus` true, verification `SUCCESS`, DKIM `SUCCESS` with signing on, MAIL FROM `SUCCESS`. | SES |

**Before this deployment:**

- the SES account was in the sandbox (200 a day, 1 a second), healthy, with account-level suppression of bounces and
  complaints;
- it had no identities and no configuration sets.

**Not done:**

- step 6: the production access request, which the owner submits in the console;
- steps 7–8: the access key and `.env`, with the API 0.4.0 deploy. Until then no key exists, and production keeps
  `MAIL_TRANSPORT=none`.
