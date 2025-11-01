# HTTP API

Base URL: `http://localhost:8080/api`. Request and response bodies are JSON unless specified otherwise. Error responses contain `status`, `message`, `path`, and `timestamp`. IDs are UUIDs; each import is a new immutable version even if the document is identical.

## Specifications

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/specs` | Import `{ "name": "Catalog", "content": "<JSON or YAML string>" }`; returns 201 and the version |
| POST | `/specs/upload` | Multipart fields `name` and `file`; returns 201 |
| GET | `/specs` | List version summaries, newest first |
| GET | `/specs/{id}` | Retrieve document and metadata |

Imports allow at most 2 MB and 200 operations. External references, unresolved references, non-3.0 OpenAPI versions, and parser validation errors return 400.

```bash
curl -F 'name=Catalog API' -F 'file=@examples/catalog-v1.yaml' \
  http://localhost:8080/api/specs/upload
```

Use `curl.exe` in Windows PowerShell to avoid its `curl` alias.

## Suites

| Method | Path | Behavior |
| --- | --- | --- |
| POST | `/suites` | Generate with `{ "specId": "...", "name": "Catalog regression" }`; returns 201 |
| GET | `/suites` | List suites including definitions and configuration |
| GET | `/suites/{id}` | Retrieve a suite |
| PUT | `/suites/{suiteId}/tests/{testId}` | Replace the entire editable test config; returns updated suite |

Test IDs are stable UUIDs derived from `METHOD /path`. They are unique within each suite. A config has `enabled`, `disabledReason`, `pathParams`, `queryParams`, `requestHeaders`, optional `requestBody`, `expectedHeaders`, `assertions`, and `maxLatencyMs`. All maps and the assertions list must be provided. `maxLatencyMs` is 1–120000. Request headers cannot override Host or transport framing headers. Assertions are `{ "pointer": "/id", "expected": 1 }` objects.

The generated schema and expected status are tied to the specification and are not changed by this endpoint. Generate another suite to adopt a different contract. Editing a suite does not change requests already selected for an in-progress or queued run.

## Runs

```http
POST /api/runs
Content-Type: application/json

{
  "suiteId": "SUITE_ID",
  "baseUrl": "http://localhost:8080/demo-api",
  "parallelism": 4,
  "timeoutMs": 5000
}
```

Returns **202 Accepted** with the queued run. Poll `GET /api/runs/{id}`; its response is `{ "run": {...}, "results": [...] }`.

- `parallelism`: 1 to `platform.workers` (default 4; worker maximum defaults to 8).
- `timeoutMs`: 100–120000 (default 5000). Configures connection and socket-read timeouts.
- Optional `testIds`: execute only these tests. Unknown IDs and empty selections return 400.
- Optional `compareToSpecId`: execute existing tests affected by the candidate specification. Mutually exclusive with `testIds`. The suite's spec is the baseline; its expectations are retained.
- Omitting both selectors executes the full suite. A comparison with no affected tests returns 400, not a full-suite rerun.
- Disabled tests produce SKIPPED results and no outbound request.
- `baseUrl` can include a base path, but no credentials, query, or fragment. Its exact hostname must be allowlisted. Redirects are not followed.
- A full run queue returns 429 and records an ERROR run for visibility.

Run states: `QUEUED → RUNNING → PASSED | FAILED | SKIPPED | ERROR | INTERRUPTED`. Individual request errors count toward the run's failed count. An all-skipped run is SKIPPED. Result rows are persisted as they finish; summary counts are finalized when the run finishes. Interrupted runs may have fewer results than selected tests.

`GET /api/runs?suiteId=...&limit=50` lists newest runs. Both parameters are optional; limit is 1–200. `GET /reports/{runId}` (outside `/api`) renders the HTML report. Use the browser's Print / Save as PDF or save the HTML response. Inline report styling works offline; history links require the application.

## Change impact

```http
GET /api/changes?oldSpecId=BASELINE&newSpecId=CANDIDATE&suiteId=SUITE
```

The optional suite must belong to the baseline specification. The response contains:

```json
{
  "oldSpecId": "BASELINE",
  "newSpecId": "CANDIDATE",
  "changes": [{
    "operation": "GET /products/{id}",
    "kind": "MODIFIED",
    "reasons": ["Dependency changed: #/components/schemas/Product"]
  }],
  "affectedTestIds": ["TEST_ID"],
  "addedOperations": []
}
```

Without `suiteId`, changes are still reported, but affectedTestIds is empty. Added operations are reported separately and cannot map to existing baseline tests. An identical document with reordered JSON object keys produces no changes; array ordering remains significant.

## Health

`GET /actuator/health` returns Spring Boot health (including database connectivity). Demo APIs live at `/demo-api` and `/demo-v2` only with the `demo` profile.
