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

Seed links expire after a few days (the follow-ups end and the appointments pass). To reseed, run `docker compose down -v` (this wipes the database volume), then `docker compose up -d` and restart the app.

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
| Patient (access code, no JWT) | `GET/POST /api/public/q/{code}` |
| Local only | `GET /dev/token` |

Patient access codes are 256-bit random values; only their SHA-256 is stored, and every unusable code (malformed, unknown, revoked, expired, closed, cancelled) returns the same 404. The business time zone is America/Toronto.

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

## Running tests

```
mvn -B clean test
```

Tests use an in-memory H2 database and a mocked `JwtDecoder`, so they need neither Postgres nor network access.
