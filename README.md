# be-aftercare-api
Backend Api for health app and tracking

## Configuration

The API is an OAuth2 resource server: every `/api/**` call needs an Auth0 access token (RS256 JWT) whose issuer and audience match the settings below. Configuration is read from environment variables; no secrets are stored in the repo.

| Variable | Required | Default | Description |
| --- | --- | --- | --- |
| `DB_URL` | no | `jdbc:postgresql://localhost:5432/aftercare` | JDBC URL |
| `DB_USERNAME` | no | `postgres` | Database user |
| `DB_PASSWORD` | yes | none | Database password |
| `AUTH0_ISSUER_URI` | yes | none | Auth0 issuer, e.g. `https://<tenant>.ca.auth0.com/` (keep the trailing slash) |
| `AUTH0_AUDIENCE` | yes | none | Auth0 API identifier the token must be issued for |
| `CORS_ALLOWED_ORIGINS` | no | `http://localhost:4200` | Comma-separated frontend origins |
| `DDL_AUTO` | no | `update` | Hibernate `ddl-auto` mode |

DDL_AUTO defaults to `update`; switch to `validate` once schema migrations (e.g. Flyway) are added.

## Running tests

```
mvn -B clean test
```

Tests use an in-memory H2 database and a mocked `JwtDecoder`, so they need neither Postgres nor network access.
