import asyncio
import json
from datetime import date

import pytest
from fastapi import HTTPException

from app import main


CALLER = main.CallerContext(tenant="Atlas", role="employee")


def test_policy_is_filtered_by_caller_date_and_approval():
    response = asyncio.run(main.answer_question("annual certification reimbursement limit", date(2026, 9, 21), CALLER))

    assert response.status == "ANSWERED"
    assert [citation.chunk_id for citation in response.citations] == ["atlas-cert-current"]
    assert response.citations[0].quote == "The annual certification reimbursement limit for employees is INR 25000."


def test_home_office_disagreement_is_conflict():
    response = asyncio.run(main.answer_question("annual home-office allowance", date(2026, 9, 21), CALLER))

    assert response.status == "CONFLICT"
    assert response.answer is None
    assert {citation.chunk_id for citation in response.citations} == {"atlas-home-office-a", "atlas-home-office-b"}


def test_effective_interval_start_inclusive_end_exclusive():
    before_start = asyncio.run(main.answer_question("certification limit", date(2026, 6, 1), CALLER))
    at_end = asyncio.run(main.answer_question("certification limit", date(2027, 1, 1), CALLER))

    assert "atlas-cert-current" in {citation.chunk_id for citation in before_start.citations}
    assert "atlas-cert-current" not in {citation.chunk_id for citation in at_end.citations}


def test_reordering_and_exact_policy_duplicates_do_not_change_result(monkeypatch, tmp_path):
    policies = json.loads(main.DATA_PATH.read_text(encoding="utf-8"))
    current_certification = next(item for item in policies if item["id"] == "atlas-cert-current")
    policies = list(reversed(policies)) + [current_certification]
    path = tmp_path / "policies.json"
    path.write_text(json.dumps(policies), encoding="utf-8")
    monkeypatch.setattr(main, "DATA_PATH", path)

    response = asyncio.run(main.answer_question("annual certification reimbursement limit", date(2026, 9, 21), CALLER))

    assert response.status == "ANSWERED"
    assert [citation.chunk_id for citation in response.citations] == ["atlas-cert-current"]


def test_other_tenant_and_injection_example_never_cited():
    response = asyncio.run(main.answer_question("home-office allowance", date(2026, 9, 21), CALLER))

    assert "boreal-home-office-current" not in {citation.chunk_id for citation in response.citations}
    assert "atlas-injection-example" not in {citation.chunk_id for citation in response.citations}


class TimeoutProvider:
    async def generate(self, question, records):
        await asyncio.sleep(0.05)
        return {}


class UnavailableProvider:
    async def generate(self, question, records):
        raise RuntimeError("provider offline")


class MalformedProvider:
    async def generate(self, question, records):
        return {"unexpected": True}


@pytest.mark.parametrize(
    ("provider", "expected_status", "expected_code"),
    [
        (TimeoutProvider(), 504, "PROVIDER_TIMEOUT"),
        (UnavailableProvider(), 503, "PROVIDER_FAILURE"),
        (MalformedProvider(), 503, "PROVIDER_FAILURE"),
    ],
)
def test_provider_failures_are_not_policy_outcomes(monkeypatch, provider, expected_status, expected_code):
    monkeypatch.setattr(main, "PROVIDER", provider)
    monkeypatch.setattr(main, "GENERATION_TIMEOUT_SECONDS", 0.01)

    with pytest.raises(HTTPException) as error:
        asyncio.run(main.answer_question("certification limit", date(2026, 9, 21), CALLER))

    assert error.value.status_code == expected_status
    assert error.value.detail["code"] == expected_code