# Meldiq API contract

The canonical contract every client (Android, web, iOS) builds against. Architecture 1.0, step C2
([plan §4](../architecture/ARCHITECTURE_1_0_PLAN.md)).

| File | What |
|---|---|
| [`openapi.json`](openapi.json) | OpenAPI 3.1 for the documented HTTP routes, with the error-code catalog as `x-error-codes` |
| [`realtime.schema.json`](realtime.schema.json) | JSON Schema of every WebSocket frame on `/v1/ws` (`ServerEvent`, `ClientEvent`) |
| [`../../shared/contracts/fixtures/`](../../shared/contracts/fixtures) | Golden examples: real answers (`http/`), error answers (`http/errors/`) and frames (`realtime/`) captured from the server's test suite |

**Source of truth:** the zod schemas in `server/src/shared/contracts/` (HTTP routes in `http/`, frames in
`realtime.ts`) and the error catalog in `server/src/shared/errors.ts`. The routes validate their input with these
schemas, and every server test checks each answer and frame it sees against them. Never edit the generated files by
hand:

```bash
cd server && npm run contracts
```

Fixtures are refreshed by deleting the files to refresh and running the suite with `MELDIQ_WRITE_FIXTURES=1`.
Credentials in them are replaced by `<redacted>`; ids and times are from test runs.

**Rules for clients:**
- Ignore fields and frame names you don't know; fields are only ever added within an API version.
- Errors are always `{ "error": { "code", "message", "details"? } }`; branch on `code`, show `message`.
- Unknown queue item kinds or payloads get a placeholder, never a silent drop.
- Decode every fixture in your unit tests with your own models, so a contract change shows up as a failing test.

**Coverage:** 30 of the server's routes are documented so far (system, messaging and queue, history, files, calls);
the rest are added area by area. Undocumented routes still answer with the same error envelope (checked on every test).
Known exception: a path that matches no route answers Fastify's own 404 body (`{ message, error, statusCode }`), which
the Android app relies on to detect an older server; it will move to the envelope in a compatible way.
