# Design Note

## Consequential choice

Policy retrieval is deterministic and filters records by tenant, role, approval, evidence type, and effective interval before relevance scoring. The offline generation provider sees only those eligible records. A matching annual amount is reported as policy text, not as a remaining balance or payable decision. This keeps the offline path inspectable and ensures the injection-example passage can never become answer context.

I rejected a free-form LLM-first implementation because it would make eligibility and citations dependent on model behavior and introduce a credential/network requirement. The `GenerationProvider` interface leaves room for an optional model without making it authoritative for identity, evidence scope, or citations.

## Limitation and unfinished work

Extraction is intentionally small: regex-based benefit and currency/amount/reference parsing plus text extraction from PDFs. It is not OCR, does not understand arbitrary layouts, and reports multiple distinct monetary amounts as ambiguous. Production authentication, persistent audit storage, and integration with a real approval system are not implemented. **Time spent:** 0.3 hours of assistant elapsed time; candidate review time is not included.

AI assistance was used to scaffold the implementation, tests, and documentation. The candidate should review and be prepared to explain and modify all code before submission.

## Production design note (under 400 words)

For 10,000 daily requests containing personal information, I would deploy the Spring API and Python worker as separate containerized services behind a managed ingress, in a private network. The public API would use the organization’s identity provider and enforce tenant/role authorization from trusted claims or an authoritative directory, never request documents. A durable queue and encrypted object storage would separate intake from slow approval-system calls; status and idempotency keys would make retries explicit. Policy records would be versioned and audited, and each decision would retain the exact eligible policy IDs and source quotations used.

The first security risks are cross-tenant disclosure, prompt injection through documents or policies, excessive retention of employee information, unsafe file parsing, secret exposure, and an approval integration accidentally causing duplicate or unauthorized actions. I would use least-privilege service identities, private service networking, encryption in transit and at rest, upload size/type limits and malware scanning, redacted structured logs, retention/deletion controls, and human approval gates. Model output would remain untrusted and could not trigger payment or modify identity/policy scope.

Operationally, I would add request tracing by batch/document ID, metrics for extraction and dependency failures, bounded retries only for explicitly idempotent operations, dead-letter handling, provider health checks, and tested recovery/retention procedures. I would clarify approval-system APIs, latency and rate limits, idempotency guarantees, escalation ownership, policy precedence/versioning, data residency and retention rules, peak batch sizes, service-level objectives, and the required human-review workflow before committing to a delivery date.