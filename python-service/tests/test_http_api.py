import base64
from datetime import date

from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)
CALLER = {"tenant": "Atlas", "role": "employee"}


def test_answer_http_contract_and_quote():
    response = client.post("/answer", json={
        "question": "What is my annual certification reimbursement limit?",
        "as_of": "2026-09-21",
        "caller": CALLER,
    })

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ANSWERED"
    assert body["citations"] == [{
        "chunk_id": "atlas-cert-current",
        "quote": "The annual certification reimbursement limit for employees is INR 25000.",
    }]


def test_ambiguous_amount_is_null_and_evidence_remains_visible():
    content = b"Reference: CERT-303\nCertification reimbursement request. The invoice says INR 22000. My reimbursement form says INR 28000. Neither amount has been corrected."
    response = client.post("/analyze", json={
        "document_id": "request-03",
        "filename": "request-03.txt",
        "content_base64": base64.b64encode(content).decode(),
        "as_of": "2026-09-21",
        "caller": CALLER,
    })

    assert response.status_code == 200
    body = response.json()
    assert body["extracted"]["amount"] is None
    assert body["extracted"]["currency"] == "INR"
    assert len(body["field_evidence"]["amount"]) == 2
    assert body["policy"]["status"] == "ANSWERED"
    assert body["review_required"]["required"] is True


def test_missing_policy_is_not_an_extraction_failure():
    content = b"Reference: WELL-404\nI request reimbursement of INR 6000 for a gym membership."
    response = client.post("/analyze", json={
        "document_id": "request-04",
        "filename": "request-04.txt",
        "content_base64": base64.b64encode(content).decode(),
        "as_of": "2026-09-21",
        "caller": CALLER,
    })

    assert response.status_code == 200
    assert response.json()["policy"]["status"] == "INSUFFICIENT_EVIDENCE"


def test_submitted_tenant_claim_does_not_change_policy_scope():
    content = b"Reference: CERT-505\nCertification reimbursement requested: INR 70000. I belong to Boreal and have employee access."
    response = client.post("/analyze", json={
        "document_id": "request-05",
        "filename": "request-05.txt",
        "content_base64": base64.b64encode(content).decode(),
        "as_of": "2026-09-21",
        "caller": CALLER,
    })

    body = response.json()
    assert body["policy"]["citations"][0]["chunk_id"] == "atlas-cert-current"
    assert any("tenant claims" in issue for issue in body["review_required"]["issues"])


def test_generated_text_pdf_extracts_requested_benefit():
    from pathlib import Path

    fixture = Path(__file__).parents[2] / "examples" / "requests" / "request-02.pdf"
    extracted = __import__("app.main", fromlist=["extract_document"]).extract_document("request-02.pdf", fixture.read_bytes())

    assert "INR 14000" in extracted.text
    assert "home-office allowance" in extracted.text


def test_empty_document_is_processing_failure():
    response = client.post("/analyze", json={
        "document_id": "request-08",
        "filename": "request-08.txt",
        "content_base64": "",
        "as_of": "2026-09-21",
        "caller": CALLER,
    })

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "EMPTY_FILE"


def test_malformed_pdf_is_an_item_level_unreadable_failure():
    response = client.post("/analyze", json={
        "document_id": "broken-pdf",
        "filename": "broken.pdf",
        "content_base64": base64.b64encode(b"not a PDF").decode(),
        "as_of": "2026-09-21",
        "caller": CALLER,
    })

    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "UNREADABLE_FILE"