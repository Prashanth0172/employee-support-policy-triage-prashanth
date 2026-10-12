# Employee Support Policy Triage

Local candidate-assessment implementation. Spring Boot owns the public API, caller lookup, validation, multipart manifest checks, duplicate tracking, and safe batch responses. Python owns UTF-8/PDF text extraction, policy eligibility and retrieval, the deterministic offline answer provider, and reimbursement review findings.

## Prerequisites

- Java 21 and Maven 3.9+
- Python 3.11 or newer
- `curl` for the API examples

The API runs on port 8080; the internal Python service runs on port 8000. No key, model account, database, or network access is needed after Python dependencies are installed.

## Run

Generate the supplied synthetic request files (request 06 is byte-for-byte request 01; request 08 is zero bytes):

```sh
python3 scripts/create_fixtures.py
```

In terminal one, install and start Python:

```sh
python3 -m venv python-service/.venv
source python-service/.venv/bin/activate
pip install -r python-service/requirements.txt
cd python-service
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

In terminal two, start the public Spring Boot API:

```sh
mvn -f java-api/pom.xml spring-boot:run
```

Run automated tests:

```sh
cd python-service && .venv/bin/python -m pytest tests -q
mvn -f java-api/pom.xml test
```

The deterministic `OfflineModel` is used by default. The provider boundary has no automatic retries and a two-second generation timeout. The Spring-to-Python HTTP client has bounded connect and read timeouts configurable with `POLICY_SERVICE_URL` and the `policy-service.*-timeout` settings in `java-api/src/main/resources/application.yml`.

## API

Both public endpoints require `X-Caller-Id`. The only accepted IDs are `atlas-employee-01`, `atlas-contractor-01`, and `boreal-employee-01`; tenant and role are looked up server-side. Missing or unknown callers return 401. Invalid questions, dates, metadata, manifest identifiers, filenames, or missing/extra file parts return 400. Dependency failures on `/answer` are non-2xx (`DEPENDENCY_FAILURE`).

### Policy question

```sh
curl -sS http://localhost:8080/answer \
  -H 'Content-Type: application/json' \
  -H 'X-Caller-Id: atlas-employee-01' \
  -d '{"question":"What is my annual certification reimbursement limit?","as_of":"2026-09-21"}'
```

Response is `{ "status", "answer", "citations" }`. A citation is `{ "chunk_id", "quote" }`, with the exact policy ID and verbatim passage. Business outcomes are `ANSWERED`, `INSUFFICIENT_EVIDENCE`, or `CONFLICT`; only the first has an answer, and insufficient evidence has no citations.

Representative response is saved at [`examples/responses/answer.json`](examples/responses/answer.json).

### Synchronous batch

The JSON metadata is one multipart part named `metadata`; repeat the `files` part for every manifest filename. The complete supplied mixed batch is described in [`examples/demo-manifest.json`](examples/demo-manifest.json).

```sh
curl -sS http://localhost:8080/batches \
  -H 'X-Caller-Id: atlas-employee-01' \
  -F 'metadata=@examples/demo-manifest.json;type=application/json' \
  -F 'files=@examples/requests/request-01.txt;filename=request-01.txt' \
  -F 'files=@examples/requests/request-02.pdf;filename=request-02.pdf' \
  -F 'files=@examples/requests/request-03.txt;filename=request-03.txt' \
  -F 'files=@examples/requests/request-04.txt;filename=request-04.txt' \
  -F 'files=@examples/requests/request-05.txt;filename=request-05.txt' \
  -F 'files=@examples/requests/request-06.txt;filename=request-06.txt' \
  -F 'files=@examples/requests/request-07.txt;filename=request-07.txt' \
  -F 'files=@examples/requests/request-08.txt;filename=request-08.txt' \
  -o examples/responses/mixed-batch-response.json
```

The checked-in mixed response can be regenerated offline without Spring Boot:

```sh
python-service/.venv/bin/python scripts/render_reference_response.py
```

This exercises the same Python analysis endpoint for every fixture and saves `examples/responses/mixed-batch-response.json`. The `curl` example above demonstrates the public Spring Boot endpoint and can write its response to that same path.

Each result contains `document_id`, `processing_status`, `extracted`, `field_evidence`, `policy`, `review_required`, `duplicate_of`, and `error`. `extracted` is `{benefit, amount, currency, reference}`; unsupported or ambiguous values are `null`. `field_evidence` maps each field to an array of `{quote}` objects, preserving conflicting amount quotations even when the extracted amount is null. `policy` uses the `/answer` shape or is null if no benefit is identifiable or processing fails. `review_required` is `{required: true, issues: [...]}`. `duplicate_of` points to the first identical file's manifest ID. `error` is null on success, otherwise `{code, message}`.

Stable item error codes are `EMPTY_FILE`, `UNREADABLE_FILE`, `DEPENDENCY_TIMEOUT`, and `DEPENDENCY_FAILURE`. Readable missing/ambiguous values complete successfully. Batch item failures do not block other entries. Logs contain batch/document identifiers and safe outcome details, not submitted text or full policy passages.

## Policy and evidence behavior

The supplied 12 policy IDs, wording, scope, and effective intervals live in `python-service/policy-data/policies.json`. `evidence_type` is an additional corpus classification: the supplied injection demonstration is retained verbatim but labeled as a non-policy example, so it is never eligible evidence. Eligibility requires policy type, Approved state, matching tenant and role, and `effective_from <= as_of < effective_to`. Filtering occurs before relevance scoring. Simultaneous eligible disagreement returns `CONFLICT`; no precedence is invented. Exact duplicate passages do not multiply evidence.

Requests and extracted text never set identity or become policy sources. The offline provider receives only eligible policy passages; the current deterministic provider does not use request text for answer generation. Annual policy limits do not imply remaining balance, benefit eligibility, or a payable amount. Every completed report requires human review, and the service does not approve, pay, or message employees.

## Tests and known gaps

Python tests cover tenant/role/date/approval eligibility, boundary dates, reordering and duplicate passages, exact source quotations, policy conflict, ambiguous extraction, unavailable/timed-out/malformed provider output, missing policy, and unreadable empty input. Spring tests cover caller rejection, public answer shape, calendar date validation, manifest order, and duplicate reporting. The mixed demo exercises item-level failure alongside completed documents and includes a text-based PDF.

Not covered: adversarially malformed PDFs and large-file/resource-exhaustion behavior beyond the configured multipart limits. This is a local exercise, not production authentication or secure multi-tenant deployment.