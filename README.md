# be-aftercare-api
Backend Api for health app and tracking

## Configuration

The API is an OAuth2 resource server: every `/api/**` call needs an Auth0 access token (RS256 JWT) whose issuer and audience match the settings below. Configuration is read from environment variables; no secrets are stored in the repo.

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `DB_URL` | no | `jdbc:postgresql://localhost:5432/aftercare` | JDBC URL |
| `DB_USERNAME` | no | `postgres` | Database user |
| `DB_PASSWORD` | yes | none | Database password |
| `AUTH0_ISSUER_URI` | yes (not for profile `local`) | none | Auth0 issuer, e.g. `https://<tenant>.ca.auth0.com/` (keep the trailing slash) |
| `AUTH0_AUDIENCE` | yes (not for profile `local`) | none | Auth0 API identifier the token must be issued for |
| `CORS_ALLOWED_ORIGINS` | no | `http://localhost:4200` | Comma-separated frontend origins |
| `PHI_ENCRYPTION_KEY` | yes (not for profile `local`, which has a dev-only key) | none | Base64 of 32 random bytes (`openssl rand -base64 32`). AES-256-GCM key for the RAMQ number **and for the stored access-link codes** (`code_enc`). Startup fails without it. Keep it separate from DB backups |
| `FLYWAY_BASELINE_ON_MIGRATE` | no | `false` | Set to `true` once, only to adopt a database created by the previous (`ddl-auto`) release; see [Migrations](#migrations) |
| `BOOTSTRAP_ADMIN_SUB` | first start only | empty | Auth0 `sub` of the first administrator; see [Roles & permissions](#roles--permissions) |
| `FRONTEND_BASE_URL` | yes with a real email/SMS provider | empty | `https://` base URL of the web app, used for the `/q/{code}` links in messages (no trailing slash) |
| `EMAIL_PROVIDER` | no | `log` | `log` (default, delivers nothing) or `smtp` |
| `EMAIL_FROM` | with `smtp` | empty | Sender, e.g. `Clinique Saint-Laurent <no-reply@clinique.example>` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` | `SMTP_HOST` with `smtp` | empty, `587`, empty, empty | SMTP server |
| `SMTP_AUTH`, `SMTP_STARTTLS` | no | `true`, `true` | SMTP authentication and STARTTLS |
| `SMS_PROVIDER` | no | `log` | `log` (default, delivers nothing) or `twilio` |
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER` | with `twilio` | empty | Twilio credentials and the sending number in E.164 (`+15145550100`) |
| `NOTIFICATIONS_MAX_PER_HOUR` | no | `300` | Hourly cap on messages sent by this instance |
| `RETENTION_PURGE_ENABLED` | no | `false` | Enables the scheduled retention job; see [Retention](#data-retention) |
| `RETENTION_DRY_RUN` | no | `true` | While `true` the scheduled job only counts; real deletion needs `false` **and** `RETENTION_PURGE_ENABLED=true` |
| `RATE_LIMIT_ENABLED` | no | `true` | Rate limits on `/api/public/**`; see [Public rate limits](#public-rate-limits) |
| `RATE_LIMIT_TRUSTED_PROXIES` | behind a proxy | empty | Comma-separated IPs/CIDRs of your reverse proxy or load balancer (the only peers whose `X-Forwarded-For` is honoured) |
| `JOBS_ENABLED` | no | `true` | `false` turns every scheduled job off on this instance |

`DDL_AUTO` no longer exists: Hibernate only **validates** the schema (`ddl-auto: validate` in every profile). Schema changes are Flyway migrations.

Startup fails with a clear message (never containing a secret) when `EMAIL_PROVIDER=smtp` has no `SMTP_HOST`/`EMAIL_FROM`, `SMS_PROVIDER=twilio` has a blank credential or a `TWILIO_FROM_NUMBER` that is not E.164, a provider value is unknown, or a real provider is used without an `https://` `FRONTEND_BASE_URL` (profile `local` also accepts `http://localhost`).

> All clinical content (questions, thresholds, flags) is a development placeholder. A clinician must validate it before use with real patients.

> The API is not a FHIR server. It stores and serves FHIR-shaped JSON for a documented subset. It exposes no FHIR REST endpoints, search, `_format` or `Bundle`s, and does no profile validation.

The Postman collections in `Postman/` predate the current API and are stale.

## Local development (no Auth0)

Profile `local` uses Postgres 16 from docker-compose, an HS256 dev token endpoint and seed data. It binds to `127.0.0.1` only.

0. **Once, for this release**: `docker compose down -v`. The schema is now built by Flyway and the seeder reseeds; an old `ddl-auto` volume would not match (see [Migrations](#migrations) to adopt a real database instead).
1. `docker compose up -d`
2. Leave `AUTH0_ISSUER_URI` unset (startup fails if it is set together with profile `local`, or if `prod`, `staging` or `qa` is also active).
3. `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
4. `curl localhost:8080/dev/token` returns `{"accessToken": "...", "tokenType": "Bearer", "expiresIn": 28800, "sub": "dev|admin-1"}`. Use it as `Authorization: Bearer <accessToken>`. With no parameter you are the seeded administrator. To test the other roles use `?role=`:

   | `/dev/token?role=` | `sub` | Seeded staff member |
   | --- | --- | --- |
   | `ADMIN` | `dev\|admin-1` | Dev Admin |
   | `PHYSICIAN` | `auth0\|seed-surgeon` | Dr Alain Bouchard |
   | `NURSE` | `dev\|nurse-1` | Dev Nurse |
   | `RECEPTION` | `dev\|reception-1` | Dev Reception |

   `?sub=<sub>` (`[A-Za-z0-9|_.:-]{1,64}`) issues a token for any subject; `?sub=dev|unlinked` has no staff member and gets `403 NOT_STAFF`, which is how to test the onboarding case. Passing both parameters, or an invalid value, is a 400 `VALIDATION_FAILED`.
5. On first start (empty database) demo patients, follow-ups, alerts and appointments are seeded. The patient links are logged at INFO and are fixed:
   - `http://localhost:4200/q/dev-marie-checkin-code-00000000000000000000` (Marie, daily check-in)
   - `http://localhost:4200/q/dev-jean-checkin-code-000000000000000000000` (Jean, daily check-in)
   - `http://localhost:4200/q/dev-jean-intake-code-0000000000000000000000` (Jean, pre-appointment questionnaire)

   The same `/q/{code}` links also open the message thread (Marie and Jean have an active follow-up link; Sophie's thread is open through her upcoming appointment link).
6. A second seed block (patient file data) has its own guard (no staff yet), so it also runs on a database seeded by an earlier version:
   - clinic settings ("Clinique Saint-Laurent"; email and SMS on, both using the **logging senders**, so nothing leaves your machine; check-in and appointment reminders on) and 5 staff (Dev Nurse `dev|nurse-1`, Dr Alain Bouchard `auth0|seed-surgeon`, Dr Nadia Lefebvre, Isabelle Morin, Marc Pelletier, inactive) with access roles NURSE, PHYSICIAN, PHYSICIAN, NURSE and RECEPTION. Two more logins are created on every start if missing: Dev Admin `dev|admin-1` (ADMIN) and Dev Reception `dev|reception-1` (RECEPTION);
   - contact preferences: Marie granted consent, SMS, French; Jean granted, email, English; Sophie unknown (so "Send link" answers `NO_CONSENT` for her). Seed links store their code encrypted, so "Send link" works on seed data;
   - Marie: date of birth, RAMQ `TREM68541212` (shown masked `TREM •••• ••12`), 2 allergies, 3 medications, a procedure, 2 diagnoses, an emergency contact, a care team, 2 notes (one by the surgeon, so not editable by Dev Nurse), 2 PDF documents and 3 messages (1 unread by staff);
   - Jean: RAMQ, medication, procedure, care team, 1 message; Sophie: contact and 1 note.

The missed check-in job runs on a schedule, so the seeded gaps (days without a check-in) produce `MISSED_CHECKIN` alerts on its first tick after the 20:00 cutoff. That is expected.

Seed links expire after a few days (the follow-ups end and the appointments pass). To reseed or reset the local database, run `docker compose down -v` (this wipes the database volume), then `docker compose up -d` and restart the app.

`mvn spring-boot:run -Dspring-boot.run.profiles=local` works too; `./mvnw` is the Maven wrapper (it downloads Maven on first use).

Optional variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (defaults match docker-compose), `DEV_JWT_SECRET` (at least 32 bytes).

## Endpoints overview

Staff endpoints need `Authorization: Bearer <JWT>` (Auth0, or the dev token in profile `local`). Errors are `{"status","error","message","fieldErrors"}`.

| Area | Endpoints |
| --- | --- |
| Me | `GET /api/me` (identity and permissions; `403 NOT_STAFF` for a login without an active staff member) |
| Patients | `POST /api/patients/register`, `GET /api/patients` (with active follow-up and unresolved alert count; both `null` for reception), `GET/DELETE /api/patients/{id}` |
| Contact preferences, notifications | `GET/PUT /api/patients/{id}/contact-preferences`, `GET /api/patients/{id}/notifications?page=&size=`, `POST /api/follow-ups/{id}/send-link`, `POST /api/appointments/{id}/send-link` (202) |
| Follow-ups | `POST/GET /api/patients/{id}/follow-ups`, `POST /api/follow-ups/{id}/access-code`, `POST /api/follow-ups/{id}/close`, `GET /api/patients/{id}/check-ins` |
| Alerts | `GET /api/alerts?status=`, `GET /api/patients/{id}/alerts`, `POST /api/alerts/{id}/acknowledge`, `POST /api/alerts/{id}/resolve` |
| Appointments | `POST/GET /api/appointments`, `GET /api/appointments/metrics`, `GET/PUT/DELETE /api/appointments/{id}`, `POST /api/appointments/{id}/access-code`, `/review`, `/status`, `GET /api/patients/{id}/appointments` |
| Forms | `GET /api/questionnaires`, `GET /api/questionnaires/{id}` |
| Patient file | `PUT /api/patients/{id}`, `GET /api/patients/{id}/medical`, `PUT/DELETE /api/patients/{id}/health-number`, `POST .../health-number/reveal`, `POST/PUT/DELETE /api/patients/{id}/{allergies,medications,procedures,diagnoses}[/{itemId}]`, `GET/POST/PUT/DELETE /api/patients/{id}/emergency-contacts[/{contactId}]`, `GET/POST/DELETE /api/patients/{id}/care-team[/{assignmentId}]` |
| Notes, timeline, audit | `GET /api/patients/{id}/timeline?category=&page=&size=`, `POST /api/patients/{id}/notes`, `PUT /api/notes/{id}`, `POST /api/notes/{id}/delete`, `GET /api/patients/{id}/audit?page=&size=&actorSub=`, `GET /api/audit?page=&size=&actorSub=&action=&patientId=&from=&to=` (administrators) |
| Documents | `GET/POST /api/patients/{id}/documents` (multipart `file`, `category`, optional `title`), `GET /api/documents/{id}/content`, `POST /api/documents/{id}/delete` |
| Messages (staff) | `GET /api/messages/inbox`, `GET /api/messages/unread-count`, `GET/POST /api/patients/{id}/messages`, `POST /api/patients/{id}/messages/read` |
| Settings | `GET/PUT /api/settings/clinic`, `GET/PUT /api/settings/notifications`, `GET/PUT /api/settings/retention`, `GET/POST /api/staff`, `PUT /api/staff/{id}`, `POST /api/staff/{id}/deactivate`, `/reactivate` |
| Retention | `POST /api/retention/dry-run`, `GET /api/retention/runs` |
| Reports | `GET /api/reports/summary?from=&to=` |
| Patient (access code, no JWT) | `GET/POST /api/public/q/{code}`, `GET/POST /api/public/q/{code}/messages`, `POST /api/public/q/{code}/messages/read` |
| Local only | `GET /dev/token` |

Every `/api/**` route is guarded by the permission table in `AccessPolicy` (see [Roles & permissions](#roles--permissions)); a route that is not in the table is denied. Patient access codes are 256-bit random values; only their SHA-256 is stored (a new link also stores the code encrypted, see [Notifications](#notifications)), and every unusable code (malformed, unknown, revoked, expired, closed, cancelled) returns the same 404. The business time zone is America/Toronto.

## Patient file & privacy

- **RAMQ number**: encrypted at the field level with AES-256-GCM (`PHI_ENCRYPTION_KEY`, patient id as AAD) with a masked copy stored next to it. It is revealed only by `POST /api/patients/{id}/health-number/reveal` with a mandatory reason, and every reveal is audited. Losing the key makes stored RAMQ numbers unrecoverable (re-enter them). Store the key in a secret manager, not next to DB backups.
- **Audit log** (`audit_events`): append-only, no foreign key to patients (rows survive patient deletion). It records file views, RAMQ reveals, document downloads, message views, audit-log views (first page only) and every change. Details never hold PHI values: only field names, categories, counts and reveal reasons. View events commit in their own transaction.
- **Never logged**: RAMQ (plain, masked or encrypted), date of birth, message/note/reason bodies, document titles, filenames or bytes, allergy/medication/diagnosis text. Logs carry ids and counts only. Do not enable SQL or bind logging in shared config.
- **Notes**: the author can edit for 15 minutes; afterwards immutable. "Entered in error" (author, with a reason) is a soft delete that keeps the body in the database.
- **Documents**: PDF, JPEG or PNG up to 10 MB, typed by magic bytes (the client type and extension are ignored; HEIC is rejected). Stored in Postgres (`document_blobs`, bytea) behind `DocumentStorage`. Downloads are attachments with `nosniff`, `no-store` and a locked-down CSP. Soft delete keeps the content.
- **Retention and Law 25**: nothing is purged until an administrator sets retention periods **and** ops enable the purge job (see [Data retention](#data-retention)); until then soft deletes keep content and audit rows are kept indefinitely. A privacy impact assessment, a designated privacy officer and a confidentiality-incident register are required before production; host the DB and backups in Canada. Confirm the retention periods with the privacy officer.
- **Need-to-know by role**: reception never receives clinical data; the server redacts it (hiding things in the UI is only convenience). Every denial is audited (`ACCESS_DENIED`). Patient-level "care team only" restriction is not implemented (see Follow-ups).
- **Messages to patients** (email, SMS) cross third-party infrastructure. The privacy officer must assess the providers (the SMTP host and Twilio) and the **cross-border data flows** (Twilio processes data in the United States) in the privacy impact assessment (EFVP) before real providers are enabled. Prefer Canadian-hosted providers where possible. Backups still contain purged data until they rotate; align backup retention with the retention policy.

## Messaging

One thread per patient. It is open while the patient has at least one usable access link (the same rule as `/q`: an active follow-up through its end date plus one grace day, or an open appointment before it starts). The patient reads and replies through any usable code; staff can always read but can send only while the thread is open (else 409 `MESSAGING_CLOSED`). Read state is a shared clinic inbox. Bodies are plain text (control characters rejected, CRLF normalized): 2000 characters max for messages, 5000 for notes. Patient sending is rate-limited in the database to 10 per rolling hour and 30 per rolling 24 hours per patient (429 `RATE_LIMITED`). All `/api/public/**` responses are `Cache-Control: no-store`.

## Reports

`GET /api/reports/summary?from&to` (Toronto dates, at most 366 days, default the last 28 days) runs at most 8 projection queries. Rates are 0..1 and means are rounded to 1 decimal.

- **Check-ins**: expected days of a follow-up are `dischargeDate + 1 <= d <= endDate`, before the closing date when closed, within `[from, throughDate]` where `throughDate = min(to, today - 1)` (today never counts). `received` counts check-ins on an expected day. `completionRate = received / expected` (null if none expected).
- **Follow-ups**: `activeNow` (status ACTIVE and `today <= endDate`), `started` (discharge date in range), `closed` (closing date in range).
- **Alerts** (created in range, including missed check-in alerts; a system auto-resolution counts as "resolved"): `responded` = acknowledged or resolved, mean time uses `acknowledgedAt ?? resolvedAt`; `resolved` with its mean; `unresolved` = status not RESOLVED.
- **Appointments** (scheduled in range): `questionnairesExpected = total - cancelled`; `questionnairesCompleted` = non-cancelled with an intake; `needsReviewRate = intakesWithFlags / questionnairesCompleted`; `cancellationRate = cancelled / total`; `noShowRate = noShow / (completed + noShow)`; each rate is null when its denominator is 0.
- **weeks**: one entry per Monday-based week intersecting the range (`from`/`to` clipped), with the same check-in rules and alert counts per `createdAt` date.

## Forms

Forms are FHIR R4 `Questionnaire` resources (a documented subset) stored as data, plus a sibling `rules` array for alerts (follow-up check-ins) and readiness flags (pre-appointment questionnaires). Each file in `src/main/resources/questionnaires/*.json` is a wrapper:

```json
{ "purpose": "FOLLOW_UP_CHECKIN | PRE_APPOINTMENT", "specialty": "...", "appointmentTypes": [],
  "questionnaire": { "resourceType": "Questionnaire", ... }, "rules": [ ... ] }
```

- Base strings are French (`language: fr-CA`); English uses the FHIR `translation` extension on `_title`, `_text` and `_display` and is mandatory.
- Files are validated at startup in every profile; any error fails startup.
- Definitions are immutable per (url, version). To change a form, bump `version` in a new file or edit and bump the version. A changed file with an unchanged version is ignored (a warning is logged).
- Set `"status": "retired"` to retire a form.
- Clinical disclaimer: all questions, thresholds and flags are development placeholders and need clinical validation.

This material contains content from LOINC (http://loinc.org). LOINC is copyright Regenstrief Institute, Inc. and the Logical Observation Identifiers Names and Codes (LOINC) Committee and is available at no cost under the license at http://loinc.org/license. LOINC codes used: 72514-3 (pain severity, 0-10 numeric rating) and 8310-5 (body temperature).

## Roles & permissions

Each staff member has an `accessRole` (what they may do) in addition to the existing `role` (their profession, unchanged). Roles come **only from the staff directory**: the JWT `sub` is matched to the `auth0Subject` of an **active** staff member on every request; no role claim is read from Auth0. A login without an active staff member gets `403 NOT_STAFF` everywhere (including `GET /api/me`, so the web app can show an onboarding page). Deactivating someone locks them out on their next request.

| Permission | ADMIN | PHYSICIAN | NURSE | RECEPTION |
| --- | :-: | :-: | :-: | :-: |
| `PATIENT_READ`, `PATIENT_WRITE` (demographics, contacts, contact preferences) | yes | yes | yes | yes |
| `PATIENT_DELETE` | yes | | | |
| `CLINICAL_READ`, `CLINICAL_WRITE` (medical file, care team, follow-ups, check-ins, alerts, notes, documents, messages, appointment review) | yes | yes | yes | |
| `HEALTH_NUMBER_REVEAL` (reason still mandatory) | yes | yes | yes | |
| `APPOINTMENT_READ`, `APPOINTMENT_WRITE`, `FORMS_READ` | yes | yes | yes | yes |
| `REPORTS_READ` | yes | yes | yes | |
| `AUDIT_READ` (per-patient and global audit logs) | yes | | | |
| `STAFF_READ` (directory; `auth0Subject` is `null` unless `STAFF_MANAGE`) | yes | yes | yes | yes |
| `STAFF_MANAGE` (create, edit, link logins, change role, deactivate) | yes | | | |
| `SETTINGS_READ` (clinic name and badge severities) | yes | yes | yes | yes |
| `SETTINGS_MANAGE` (clinic profile, notification and retention settings, retention dry run) | yes | | | |

PHYSICIAN and NURSE are identical in this release. ADMIN has everything, including the clinical permissions. The whole endpoint to permission table is in one file, `config/security/AccessPolicy.java`; the role to permission map is in `RolePermissions.java` (the front end only receives permissions from `GET /api/me`). Any `/api/**` route that is not in the table is denied, and a test checks that every controller mapping is covered.

RECEPTION redactions: the patient list hides the follow-up and the alert count (`null`); appointment detail hides the intake answers and flags (`intake` null, `intakeRedacted` true; `flagCount` and the status stay); the notification history shows only appointment messages; reading a patient's demographics is audited as `PATIENT_FILE_VIEWED` ("demographics").

**First administrator (bootstrap).** On an empty database nobody can manage staff yet:

1. Set `BOOTSTRAP_ADMIN_SUB` to your own Auth0 user id (`auth0|...`) and start the API.
2. Log in once. If that `sub` has no staff row **and there are zero active administrators**, an active ADMIN row is created for it (name and email from the token when present) and audited as `STAFF_BOOTSTRAPPED`.
3. In Settings, create or link the other staff members (their Auth0 ids) and give them roles.
4. Unset `BOOTSTRAP_ADMIN_SUB`. (It is ignored anyway once any active administrator exists, so a forgotten variable cannot recreate an admin later.)

**Last administrator.** The last active ADMIN cannot be demoted or deactivated (`409 LAST_ADMIN`, checked under a row lock). Deactivating yourself is allowed when another admin remains. Role changes are audited (`STAFF_ROLE_CHANGED`, detail `"NURSE->ADMIN"`). Only administrators can link logins, which also closes the old display-name spoofing issue.

## Migrations

The schema is managed by **Flyway 9.22** and Hibernate only validates it (`ddl-auto: validate`, also in tests).

```
src/main/resources/db/migration/postgresql/V1__baseline.sql   (generated from the entities, per vendor)
src/main/resources/db/migration/h2/V1__baseline.sql
src/main/resources/db/migration/common/V2__rbac.sql           (portable SQL, shared)
                                       V3__missed_checkins_and_job_locks.sql
                                       V4__notifications.sql
                                       V5__retention.sql
```

`spring.flyway.locations` is `classpath:db/migration/{vendor},classpath:db/migration/common`, so Postgres and H2 (the tests, in PostgreSQL mode) each get their own V1. A version lives either in `common` or in **every** vendor folder, never both.

**Adding a migration**

- Never edit an applied migration.
- Add `V<n+1>__<snake_description>.sql` to `db/migration/common`, using only the portable subset: `BIGINT GENERATED BY DEFAULT AS IDENTITY`, `VARCHAR(n)`, `BOOLEAN`, `INTEGER`, `DATE`, `TIME`, `TIMESTAMP(6) WITH TIME ZONE`, `ALTER TABLE ... ADD COLUMN ... DEFAULT ... NOT NULL`, `ALTER COLUMN ... SET/DROP NOT NULL`, `ADD CONSTRAINT ... CHECK/UNIQUE`, `DROP CONSTRAINT IF EXISTS`, `CREATE INDEX`. No `text`, `bytea`, `jsonb`, `SERIAL`, `ON CONFLICT`, `SKIP LOCKED`, partial indexes or functions. If vendor-specific SQL is unavoidable, add the same version to **both** `postgresql/` and `h2/`.
- **New enum value**: drop and re-add the named check, listing **all** values: `ALTER TABLE t DROP CONSTRAINT IF EXISTS t_col_check; ALTER TABLE t ADD CONSTRAINT t_col_check CHECK (col IN (...));` (the checks are named `<table>_<column>_check`, the name Postgres already gave them).
- New entity or column: write the DDL by hand to match the entity exactly (same column names, lengths and nullability). `mvn test` must pass (`validate` on H2) and the app must start with `validate` on a local Postgres.

**Existing databases**

- *Local*: `docker compose down -v`, then `docker compose up -d` and start the app (see Local development).
- *A shared database created by `ddl-auto` from the release just before this one*: start once with `FLYWAY_BASELINE_ON_MIGRATE=true`. Flyway records V1 as the baseline without running it and applies V2 onwards; then unset the variable. The precondition is that the database really matches V1: check it with the `pg_dump` comparison below. If it is older or was modified by hand, reconcile it manually first.

**How V1 was produced and verified.** `src/test/java/.../support/SchemaExportTool.java` (tag `schema-export`, skipped by default) writes Hibernate's DDL for the entities for both dialects into `target/schema/`:

```
mvn -B -Dgroups=schema-export -Dtests.excluded.groups=none -Dtest=SchemaExportTool test
```

The output was post-processed: every inline `check (col in (...))` became a table constraint named `<table>_<column>_check` (Postgres truncates the longest name to 63 characters; V1 uses the truncated one so it equals an existing database), and `drop` statements were removed. To compare with a real Postgres 16 (no Docker needed; use a throwaway cluster, never your main one):

```
initdb -D /tmp/pgA/data -A trust && pg_ctl -D /tmp/pgA/data -o "-p 55432 -k /tmp/pgA" -l /tmp/pgA/log start
createdb -h 127.0.0.1 -p 55432 -U postgres a_hib; createdb -h 127.0.0.1 -p 55432 -U postgres a_fly
# 1. the schema Hibernate builds: run the PREVIOUS release (git archive of the commit before Flyway) with DDL_AUTO=create against a_hib
# 2. the schema Flyway V1 builds: run this release against a_fly with V1 only (it may stop later at the JWT decoder; that is fine)
DB_URL=jdbc:postgresql://127.0.0.1:55432/a_fly DB_USERNAME=postgres DB_PASSWORD=x AUTH0_ISSUER_URI=https://x.invalid/ AUTH0_AUDIENCE=a \
  PHI_ENCRYPTION_KEY=$(openssl rand -base64 32) java -jar target/aftercare-api-*.jar --spring.flyway.target=1 --spring.jpa.hibernate.ddl-auto=none
# 3. normalise and compare: the diff must contain only the flyway_schema_history table
for d in a_hib a_fly; do pg_dump --schema-only --no-owner -h 127.0.0.1 -p 55432 -U postgres $d | grep -v -E '^--|restrict' | grep -v '^$' | sort > $d.sql; done; diff a_hib.sql a_fly.sql
# 4. start this release (profile local) on a_fly: V2-V5 apply and `validate` passes
```

## Jobs

Scheduled in the business time zone (America/Toronto). Each job is a component with a public `runOnce()` (tests call it directly with a mutable clock) and a separate scheduling trigger that exists only while `JOBS_ENABLED` is not `false`.

| Job | Schedule | Lock / lease |
| --- | --- | --- |
| Missed check-in alerts | every 15 min | `missed-checkin` / 10 min |
| Check-in reminders | every 10 min | `checkin-reminder` / 10 min |
| Appointment reminders | every 15 min | `appointment-reminder` / 10 min |
| Notification dispatcher | every 30 s after the last run | `notification-dispatch` / 2 min |
| Retention purge | 03:15 (`aftercare.retention.purge.cron`) | `retention` / 1 h |

**Multi-instance safety** is a portable lease row per job (`job_locks`, an `UPDATE ... WHERE locked_until <= now`, no `SKIP LOCKED`, works the same on Postgres and H2) plus unique constraints for idempotency. A lost lease only delays a run; the unique keys (one missed alert per follow-up and day, one outbox row per dedupe key) make a duplicate run harmless.

**Missed check-in alerts.** An expected day `d` (after the discharge day and after the enrolment day, up to the end date, follow-up ACTIVE) with no check-in once local time passes the cutoff (default 20:00, configurable in Settings) creates a `MISSED_CHECKIN` alert: MEDIUM, or HIGH when the previous day was also expected and missed. At most the last 2 days are caught up after an outage. A late check-in the same day auto-resolves it (`resolvedBy` `system:checkin`). Missed alerts are alerts for every purpose: they appear in `/api/alerts`, the timeline and the report alert counts, and the system auto-resolution counts as "resolved" in the report.

## Notifications

Patient email and SMS (French by default, English by patient preference) go through an **outbox** and a dispatcher job with retry and backoff (1 min, 5 min, 30 min, 2 h, 6 h; five attempts).

- **Providers.** Default: the **logging sender**, which writes one redacted line and marks the message SENT. **It does not deliver anything**; it is the default in dev, local and any environment without a provider. Real adapters: SMTP through Spring Mail (`EMAIL_PROVIDER=smtp`) and Twilio over plain HTTPS (`SMS_PROVIDER=twilio`, no SDK). Selecting a real provider without its credentials fails startup.
- **Consent gates every send.** `contactConsent` is `UNKNOWN`, `GRANTED` or `WITHDRAWN`; only `GRANTED` sends, and it cannot be reset to `UNKNOWN`. Withdrawing is a staff action (there is no STOP keyword handling). Every change is audited. Staff can always show or copy the link in person. Withdrawing cancels pending messages at send time.
- **No PHI in messages.** Templates use only the first name, the clinic name and phone, and the personal link, in four templates (follow-up link, daily check-in reminder, appointment questionnaire link and reminder) times two languages; never a specialty, diagnosis, appointment type or date, answers or the RAMQ number. The email footer states that the link is personal, that the message is automatic, the emergency number (911), Info-Sante (811) and how to stop the messages.
- **The outbox stores no body and no link.** It stores references (patient, follow-up or appointment), a template key, the language and the channel; the message is rendered at send time from live data, and a masked recipient (`m•••@example.com`, `•••-•••-0100`) is stored after an attempt. Logs carry outbox ids, statuses and counts only, never recipients with names, bodies, links, codes or credentials.
- **The `code_enc` trade-off.** Reminders must carry a working link without rotating the patient's code every day, so a new access link also stores its code encrypted (`code_enc`, AES-GCM with `PHI_ENCRYPTION_KEY`, patient id as AAD). Only the notification renderer decrypts it. Someone with both the database and the key can recover live links; someone with the database alone cannot. Links issued before this release have no `code_enc`: sending fails with `LINK_UNAVAILABLE` until staff regenerate the link. Treat the PHI key as protecting access links too.
- **Triggers.** The link message after a follow-up is enrolled or an appointment is booked (when consent and a valid address exist and the channel is on); a daily check-in reminder (between the reminder time, default 18:00, and the missed cutoff) for expected days without a check-in; an appointment reminder inside the lead window (default 48 h, 24 to 72) for questionnaires still empty, only for appointments booked before the window; and the staff "Send link" buttons (202, at most 3 per target per rolling 24 h).
- **Limits.** Automated messages wait outside 08:00 to 21:00; at most 4 automated messages per patient per rolling 24 h; `NOTIFICATIONS_MAX_PER_HOUR` per instance. A crash in the middle of a send may produce one duplicate (the row is recovered after 10 minutes).
- **Email and SMS are not delivery-tracked** in this release (no bounces, status callbacks or inbound replies).

## Data retention

Retention periods are set by an administrator in Settings and are **null (keep indefinitely) by default**: closed or expired follow-ups (with their check-ins, alerts, links and timeline rows), soft-deleted documents (with their bytes), patient messages, finished notifications (sent, failed, cancelled, skipped) and audit events. **We do not encode any legal period**; the privacy officer sets them according to the clinic's retention schedule (*calendrier de conservation*; for physician records the Collège des médecins regulation sets minimums). Audit rows are deleted only by their own period (`auditMonths`).

The scheduled purge is **disabled by default** and runs in **dry-run** mode until ops set both `RETENTION_PURGE_ENABLED=true` and `RETENTION_DRY_RUN=false`; real deletion therefore needs an administrator's setting and an ops flag. Every run, dry or real, writes a `retention_runs` row and an audit row (`RETENTION_DRY_RUN` or `RETENTION_PURGE`) holding counts only. `POST /api/retention/dry-run` always works and only counts; there is no manual real purge. A purge deletes in batches (`aftercare.retention.purge.batch-size`, 500), each in its own transaction. Not covered: active documents, appointments and intakes, whole patient files, activity rows beyond those removed with follow-ups and documents, and backups (which keep purged data until they rotate).

## Public rate limits

`/api/public/**` only, in memory with bounded LRU maps (10 000 keys each): 60 requests per minute per IP, 30 per minute per access code (keyed by the code's SHA-256, never the code), and failed lookups (404) per IP, 10 then 1 per minute. Over a limit the response is `429 RATE_LIMITED` ("Too many requests; try again later") with `Retry-After` (integer seconds) and `Cache-Control: no-store`; CORS exposes `Retry-After`. `X-Forwarded-For` is honoured only when the direct peer is in `RATE_LIMIT_TRUSTED_PROXIES` (IPs or CIDRs; the header is read right to left, skipping trusted proxies). Limits are per instance (N instances allow N times the limit) and the staff API is not rate limited in this release.

## What you must provide before production

- **Auth0**: the `sub` of the first admin (`BOOTSTRAP_ADMIN_SUB`), and the subs of the other staff to link in Settings.
- **Email (SMTP)**: host, port, username, password, TLS mode, and the `EMAIL_FROM` address and display name. The sending domain needs SPF, DKIM and DMARC.
- **SMS (Twilio)**: Account SID, Auth Token and a Canadian sending number in E.164. Registration may be required for application-to-person (A2P) traffic.
- `FRONTEND_BASE_URL` (https) of the web app.
- **Reverse proxy**: its IPs or CIDRs for `RATE_LIMIT_TRUSTED_PROXIES`.
- **From the privacy officer**: the retention periods (five values); approval of the consent wording and the FR/EN message texts; the privacy impact assessment covering the email and SMS providers and cross-border transfer.
- **Clinic decisions**: reminder time, appointment reminder lead time and missed check-in cutoff (defaults 18:00, 48 h, 20:00).

## Follow-ups (known gaps)

- Patient-level access restriction (care-team-only files, break-the-glass with a reason).
- Distinct permissions for PHYSICIAN and NURSE (orders, co-signing); a non-clinical ADMIN or "privacy officer" role without the clinical permissions; per-staff custom permissions.
- A write-only RAMQ capture for RECEPTION at registration.
- Auth0 role or claim sync, SCIM, MFA enforcement checks, and session revocation in Auth0 on deactivation (lockout is API-side only).
- Inbound SMS (STOP and replies), delivery-status webhooks (Twilio status callbacks, bounces), HTML emails, attachments, per-template editing and more languages.
- Staff notifications (email or SMS on HIGH alerts) and a digest.
- Per-staff and per-endpoint rate limits; a distributed (Redis or database) rate limiter for several instances; CAPTCHA.
- Retention for active documents, appointments and intakes, whole patient files, `patient_activity` beyond what is purged with follow-ups, documents and messages, and storage-level blob shredding; legal-hold flags; export before purge.
- Flyway undo and repair tooling; Testcontainers-based Postgres tests in CI (needs Docker); PHI key rotation for `health_number_enc` and `code_enc`.
- A global audit export (CSV) and anomaly alerts (many reveals or denials).

## Running tests

```
mvn -B clean test
```

Tests use an in-memory H2 database (PostgreSQL mode), run Flyway and then `ddl-auto: validate`, and use a mocked `JwtDecoder`, so they need neither Postgres nor network access. Staff in tests are seeded by `IntegrationTest` (`test|admin`, `test|physician`, `test|nurse`, `test|reception`, an inactive member, and the legacy `auth0|nurse-a`, who is an ADMIN so older tests keep full access); `test|unlinked` is never inserted. Jobs and rate limiting are off in the test configuration; the tests call `runOnce()` directly with a `MutableClock`.
