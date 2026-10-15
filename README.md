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
| `DDL_AUTO` | no | `update` | Hibernate `ddl-auto` mode |
| `PHI_ENCRYPTION_KEY` | yes (not for profile `local`, which has a dev-only key) | none | Base64 of 32 random bytes (`openssl rand -base64 32`). AES-256-GCM key for the RAMQ number. Startup fails without it. Keep it separate from DB backups |

DDL_AUTO defaults to `update`; switch to `validate` once schema migrations (e.g. Flyway) are added.

> All clinical content (questions, thresholds, flags) is a development placeholder. A clinician must validate it before use with real patients.

> The API is not a FHIR server. It stores and serves FHIR-shaped JSON for a documented subset. It exposes no FHIR REST endpoints, search, `_format` or `Bundle`s, and does no profile validation.

The Postman collections in `Postman/` predate the current API and are stale.

## Local development (no Auth0)

Profile `local` uses Postgres 16 from docker-compose, an HS256 dev token endpoint and seed data. It binds to `127.0.0.1` only.

1. `docker compose up -d`
2. Leave `AUTH0_ISSUER_URI` unset (startup fails if it is set together with profile `local`, or if `prod`, `staging` or `qa` is also active).
3. `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
4. `curl localhost:8080/dev/token` returns `{"accessToken": "...", "tokenType": "Bearer", "expiresIn": 28800}`. Use it as `Authorization: Bearer <accessToken>`.
5. On first start (empty database) demo patients, follow-ups, alerts and appointments are seeded. The patient links are logged at INFO and are fixed:
   - `http://localhost:4200/q/dev-marie-checkin-code-00000000000000000000` (Marie, daily check-in)
   - `http://localhost:4200/q/dev-jean-checkin-code-000000000000000000000` (Jean, daily check-in)
   - `http://localhost:4200/q/dev-jean-intake-code-0000000000000000000000` (Jean, pre-appointment questionnaire)

   The same `/q/{code}` links also open the message thread (Marie and Jean have an active follow-up link; Sophie's thread is open through her upcoming appointment link).
6. A second seed block (patient file data) has its own guard (no staff yet), so it also runs on a database seeded by an earlier version:
   - clinic settings ("Clinique Saint-Laurent") and 5 staff (Dev Nurse `dev|nurse-1`, Dr Alain Bouchard `auth0|seed-surgeon`, Dr Nadia Lefebvre, Isabelle Morin, Marc Pelletier, inactive);
   - Marie: date of birth, RAMQ `TREM68541212` (shown masked `TREM •••• ••12`), 2 allergies, 3 medications, a procedure, 2 diagnoses, an emergency contact, a care team, 2 notes (one by the surgeon, so not editable by Dev Nurse), 2 PDF documents and 3 messages (1 unread by staff);
   - Jean: RAMQ, medication, procedure, care team, 1 message; Sophie: contact and 1 note.

Seed links expire after a few days (the follow-ups end and the appointments pass). To reseed or reset the local database, run `docker compose down -v` (this wipes the database volume), then `docker compose up -d` and restart the app.

`mvn spring-boot:run -Dspring-boot.run.profiles=local` works too; `./mvnw` is the Maven wrapper (it downloads Maven on first use).

Optional variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (defaults match docker-compose), `DEV_JWT_SECRET` (at least 32 bytes).

## Endpoints overview

Staff endpoints need `Authorization: Bearer <JWT>` (Auth0, or the dev token in profile `local`). Errors are `{"status","error","message","fieldErrors"}`.

| Area | Endpoints |
| --- | --- |
| Patients | `POST /api/patients/register`, `GET /api/patients` (with active follow-up and unresolved alert count), `GET/DELETE /api/patients/{id}` |
| Follow-ups | `POST/GET /api/patients/{id}/follow-ups`, `POST /api/follow-ups/{id}/access-code`, `POST /api/follow-ups/{id}/close`, `GET /api/patients/{id}/check-ins` |
| Alerts | `GET /api/alerts?status=`, `GET /api/patients/{id}/alerts`, `POST /api/alerts/{id}/acknowledge`, `POST /api/alerts/{id}/resolve` |
| Appointments | `POST/GET /api/appointments`, `GET /api/appointments/metrics`, `GET/PUT/DELETE /api/appointments/{id}`, `POST /api/appointments/{id}/access-code`, `/review`, `/status`, `GET /api/patients/{id}/appointments` |
| Forms | `GET /api/questionnaires`, `GET /api/questionnaires/{id}` |
| Patient file | `PUT /api/patients/{id}`, `GET /api/patients/{id}/medical`, `PUT/DELETE /api/patients/{id}/health-number`, `POST .../health-number/reveal`, `POST/PUT/DELETE /api/patients/{id}/{allergies,medications,procedures,diagnoses}[/{itemId}]`, `GET/POST/PUT/DELETE /api/patients/{id}/emergency-contacts[/{contactId}]`, `GET/POST/DELETE /api/patients/{id}/care-team[/{assignmentId}]` |
| Notes, timeline, audit | `GET /api/patients/{id}/timeline?category=&page=&size=`, `POST /api/patients/{id}/notes`, `PUT /api/notes/{id}`, `POST /api/notes/{id}/delete`, `GET /api/patients/{id}/audit?page=&size=` |
| Documents | `GET/POST /api/patients/{id}/documents` (multipart `file`, `category`, optional `title`), `GET /api/documents/{id}/content`, `POST /api/documents/{id}/delete` |
| Messages (staff) | `GET /api/messages/inbox`, `GET /api/messages/unread-count`, `GET/POST /api/patients/{id}/messages`, `POST /api/patients/{id}/messages/read` |
| Settings | `GET/PUT /api/settings/clinic`, `GET/POST /api/staff`, `PUT /api/staff/{id}`, `POST /api/staff/{id}/deactivate`, `/reactivate` |
| Reports | `GET /api/reports/summary?from=&to=` |
| Patient (access code, no JWT) | `GET/POST /api/public/q/{code}`, `GET/POST /api/public/q/{code}/messages`, `POST /api/public/q/{code}/messages/read` |
| Local only | `GET /dev/token` |

Patient access codes are 256-bit random values; only their SHA-256 is stored, and every unusable code (malformed, unknown, revoked, expired, closed, cancelled) returns the same 404. The business time zone is America/Toronto.

## Patient file & privacy

- **RAMQ number**: encrypted at the field level with AES-256-GCM (`PHI_ENCRYPTION_KEY`, patient id as AAD) with a masked copy stored next to it. It is revealed only by `POST /api/patients/{id}/health-number/reveal` with a mandatory reason, and every reveal is audited. Losing the key makes stored RAMQ numbers unrecoverable (re-enter them). Store the key in a secret manager, not next to DB backups.
- **Audit log** (`audit_events`): append-only, no foreign key to patients (rows survive patient deletion). It records file views, RAMQ reveals, document downloads, message views, audit-log views (first page only) and every change. Details never hold PHI values: only field names, categories, counts and reveal reasons. View events commit in their own transaction.
- **Never logged**: RAMQ (plain, masked or encrypted), date of birth, message/note/reason bodies, document titles, filenames or bytes, allergy/medication/diagnosis text. Logs carry ids and counts only. Do not enable SQL or bind logging in shared config.
- **Notes**: the author can edit for 15 minutes; afterwards immutable. "Entered in error" (author, with a reason) is a soft delete that keeps the body in the database.
- **Documents**: PDF, JPEG or PNG up to 10 MB, typed by magic bytes (the client type and extension are ignored; HEIC is rejected). Stored in Postgres (`document_blobs`, bytea) behind `DocumentStorage`. Downloads are attachments with `nosniff`, `no-store` and a locked-down CSP. Soft delete keeps the content.
- **Retention and Law 25**: no automatic purge; soft deletes keep content and audit rows are kept indefinitely. A privacy impact assessment, a designated privacy officer and a confidentiality-incident register are required before production; host the DB and backups in Canada. Confirm the retention period with the privacy officer. There is no role-based authorization yet: every authenticated staff user sees every file, but every access is attributable.

## Messaging

One thread per patient. It is open while the patient has at least one usable access link (the same rule as `/q`: an active follow-up through its end date plus one grace day, or an open appointment before it starts). The patient reads and replies through any usable code; staff can always read but can send only while the thread is open (else 409 `MESSAGING_CLOSED`). Read state is a shared clinic inbox. Bodies are plain text (control characters rejected, CRLF normalized): 2000 characters max for messages, 5000 for notes. Patient sending is rate-limited in the database to 10 per rolling hour and 30 per rolling 24 hours per patient (429 `RATE_LIMITED`). All `/api/public/**` responses are `Cache-Control: no-store`.

## Reports

`GET /api/reports/summary?from&to` (Toronto dates, at most 366 days, default the last 28 days) runs at most 8 projection queries. Rates are 0..1 and means are rounded to 1 decimal.

- **Check-ins**: expected days of a follow-up are `dischargeDate + 1 <= d <= endDate`, before the closing date when closed, within `[from, throughDate]` where `throughDate = min(to, today - 1)` (today never counts). `received` counts check-ins on an expected day. `completionRate = received / expected` (null if none expected).
- **Follow-ups**: `activeNow` (status ACTIVE and `today <= endDate`), `started` (discharge date in range), `closed` (closing date in range).
- **Alerts** (created in range): `responded` = acknowledged or resolved, mean time uses `acknowledgedAt ?? resolvedAt`; `resolved` with its mean; `unresolved` = status not RESOLVED.
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

## Follow-ups (known gaps)

- Staff display-name spoofing via `auth0Subject` linking (no RBAC): any staff user can create or edit a staff entry with someone else's Auth0 subject, and audit/timeline entries then show that person's display name.
- Enum additions need manual SQL with `ddl-auto=update`: Hibernate does not alter existing check constraints or enum columns, so adding an enum value requires a hand-written migration on existing databases.

## Running tests

```
mvn -B clean test
```

Tests use an in-memory H2 database and a mocked `JwtDecoder`, so they need neither Postgres nor network access.
