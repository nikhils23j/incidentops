# N1 — IncidentOps

A personal full-stack service incident tracker built for practice with Java, Spring Boot, React, and TypeScript. An operator can create a validated incident, filter by status, see counts, acknowledge it, and resolve it. The backend stores incidents in a local H2 database and rejects invalid status transitions.

## Run locally

Requires Java 17+ and Node.js 20.19+ (or 22.12+).

```sh
cd n1-server
./mvnw spring-boot:run
```

In another terminal:

```sh
cd n1-web
npm ci
npm run dev
```

Open `http://localhost:5173`. The API is on `http://localhost:8080/api`; the React app reads `VITE_API_URL` if you need a different base URL. The backend's local H2 data file is written under `n1-server/data/`. No cloud account is needed.

## Checks

Run `./mvnw test` in `n1-server` and `npm run build` in `n1-web`. On the local Java 17 / Spring Boot 4.1.2 run, all three JUnit tests passed. The backend integration tests exercise creation, filtering, metrics, validation, and legal/illegal state transitions. The frontend build type-checks the TypeScript app. A separate live HTTP check passed the create → acknowledge → resolve → metrics flow against the running server.

## Design and limits

The `OPEN → ACKNOWLEDGED → RESOLVED` state machine keeps lifecycle rules in the entity. The REST layer validates input, returns explicit HTTP status codes, and exposes aggregate counts. H2 makes the project reproducible locally. This is a personal learning project, not a production system or a record of work with enterprise-authorized AI tools, AWS deployment, or professional Java experience. A production version would need authentication, migration-managed schemas, audit logs, pagination, and multi-user conflict handling in the UI.
