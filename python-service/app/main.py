from __future__ import annotations

import asyncio
import base64
import binascii
import io
import json
import logging
import os
import re
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Literal, Protocol

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from pypdf import PdfReader

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger("policy-service")
DATA_PATH = Path(os.getenv("POLICY_DATA_PATH", Path(__file__).parents[1] / "policy-data" / "policies.json"))
GENERATION_TIMEOUT_SECONDS = float(os.getenv("GENERATION_TIMEOUT_SECONDS", "2"))


class PolicyRecord(BaseModel):
    id: str
    tenant: str
    role: str
    approval_state: str
    effective_from: date
    effective_to: date
    text: str
    evidence_type: str = "policy"


class CallerContext(BaseModel):
    tenant: str
    role: str


class AnswerRequest(BaseModel):
    question: str = Field(min_length=1)
    as_of: date
    caller: CallerContext


class AnalyzeRequest(BaseModel):
    document_id: str
    filename: str
    content_base64: str
    as_of: date
    caller: CallerContext


class Citation(BaseModel):
    chunk_id: str
    quote: str


class AnswerResponse(BaseModel):
    status: Literal["ANSWERED", "INSUFFICIENT_EVIDENCE", "CONFLICT"]
    answer: str | None
    citations: list[Citation]


class GenerationProvider(Protocol):
    async def generate(self, question: str, records: list[PolicyRecord]) -> dict[str, Any]: ...


class OfflineModel:
    """Deterministic local model double; it only receives already-eligible evidence."""

    async def generate(self, question: str, records: list[PolicyRecord]) -> dict[str, Any]:
        values = {_policy_value(record.text) for record in records}
        values.discard(None)
        if len(values) > 1:
            return {"status": "CONFLICT", "answer": None, "policy_ids": [record.id for record in records]}
        if not records:
            return {"status": "INSUFFICIENT_EVIDENCE", "answer": None, "policy_ids": []}
        if len(values) > 0:
            answer = f"{records[0].text} This states an annual policy limit or allowance, not remaining balance or claim eligibility."
        else:
            answer = records[0].text
        return {"status": "ANSWERED", "answer": answer, "policy_ids": [record.id for record in records]}


PROVIDER: GenerationProvider = OfflineModel()
app = FastAPI(title="Marlabs Employee Support Policy Service", version="1.0.0")


def load_policies() -> list[PolicyRecord]:
    try:
        return [PolicyRecord.model_validate(item) for item in json.loads(DATA_PATH.read_text(encoding="utf-8"))]
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        logger.exception("policy_data_load_failed")
        raise RuntimeError("Policy data is unavailable") from exc


def eligible_records(records: list[PolicyRecord], caller: CallerContext, as_of: date) -> list[PolicyRecord]:
    eligible: list[PolicyRecord] = []
    seen: set[tuple[str, str, str, date, date, str]] = set()
    for record in records:
        if (
            record.evidence_type != "policy"
            or record.approval_state != "Approved"
            or record.tenant != caller.tenant
            or record.role != caller.role
            or not (record.effective_from <= as_of < record.effective_to)
        ):
            continue
        identity = (record.tenant, record.role, record.approval_state, record.effective_from, record.effective_to, record.text)
        if identity not in seen:
            eligible.append(record)
            seen.add(identity)
    return eligible


def _tokens(text: str) -> set[str]:
    return set(re.findall(r"[a-z]+", text.lower()))


def retrieve(question: str, records: list[PolicyRecord]) -> list[PolicyRecord]:
    question_terms = _tokens(question) - {"what", "is", "my", "the", "for", "a", "an", "and", "me", "does", "apply"}
    if not question_terms:
        return []
    scored = [(len(question_terms & _tokens(record.text)), record) for record in records]
    best_score = max((score for score, _ in scored), default=0)
    if best_score == 0:
        return []
    return [record for score, record in scored if score == best_score]


def _policy_value(text: str) -> tuple[str, str] | None:
    match = re.search(r"\b(INR|USD|EUR|GBP)\s*([\d,]+)\b", text, re.IGNORECASE)
    return (match.group(1).upper(), match.group(2).replace(",", "")) if match else None


def _validate_generation(raw: dict[str, Any], records: list[PolicyRecord]) -> AnswerResponse:
    response = AnswerResponse.model_validate({
        "status": raw["status"],
        "answer": raw["answer"],
        "citations": [
            {"chunk_id": record.id, "quote": record.text}
            for record in records
            if record.id in raw["policy_ids"]
        ],
    })
    if response.status == "ANSWERED" and (not response.answer or not response.citations):
        raise ValueError("Answered output requires an answer and citations")
    if response.status == "INSUFFICIENT_EVIDENCE" and (response.answer is not None or response.citations):
        raise ValueError("Insufficient output must not contain an answer or citations")
    if response.status == "CONFLICT" and (response.answer is not None or len(response.citations) < 2):
        raise ValueError("Conflict output requires disagreeing citations")
    return response


async def answer_question(question: str, as_of: date, caller: CallerContext) -> AnswerResponse:
    eligible = eligible_records(load_policies(), caller, as_of)
    relevant = retrieve(question, eligible)
    try:
        raw = await asyncio.wait_for(PROVIDER.generate(question, relevant), timeout=GENERATION_TIMEOUT_SECONDS)
        return _validate_generation(raw, relevant)
    except asyncio.TimeoutError as exc:
        raise HTTPException(status_code=504, detail={"code": "PROVIDER_TIMEOUT", "message": "Policy provider timed out"}) from exc
    except HTTPException:
        raise
    except Exception as exc:
        logger.warning("policy_generation_failed", extra={"error_type": type(exc).__name__})
        raise HTTPException(status_code=503, detail={"code": "PROVIDER_FAILURE", "message": "Policy provider unavailable"}) from exc


@app.post("/answer", response_model=AnswerResponse)
async def answer(request: AnswerRequest) -> AnswerResponse:
    return await answer_question(request.question, request.as_of, request.caller)


BENEFIT_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("certification", re.compile(r"\b(certification|certificate|certified)\b", re.IGNORECASE)),
    ("home-office", re.compile(r"\b(home[ -]office|desk|chair)\b", re.IGNORECASE)),
    ("wellness", re.compile(r"\b(wellness|gym|fitness)\b", re.IGNORECASE)),
    ("external-training", re.compile(r"\b(external training|training)\b", re.IGNORECASE)),
    ("rail-travel", re.compile(r"\b(rail|train travel|business trip)\b", re.IGNORECASE)),
]
MONEY_PATTERN = re.compile(r"\b(INR|USD|EUR|GBP)\s*([\d,]+(?:\.\d{1,2})?)\b", re.IGNORECASE)
REFERENCE_PATTERN = re.compile(r"(?im)^\s*Reference\s*:\s*([^\r\n]+)")


@dataclass
class ExtractedDocument:
    text: str
    content_type: str


def extract_document(filename: str, content: bytes) -> ExtractedDocument:
    if not content:
        raise ValueError("EMPTY_FILE")
    suffix = Path(filename).suffix.lower()
    if suffix == ".txt":
        return ExtractedDocument(content.decode("utf-8", errors="strict"), "text/plain")
    if suffix == ".pdf":
        reader = PdfReader(io.BytesIO(content), strict=True)
        text = "\n".join(page.extract_text() or "" for page in reader.pages)
        if not text.strip():
            raise ValueError("UNREADABLE_FILE")
        return ExtractedDocument(text, "application/pdf")
    raise ValueError("UNSUPPORTED_FILE")


def _find_benefit(text: str) -> tuple[str | None, str | None]:
    matches = [(benefit, pattern.search(text)) for benefit, pattern in BENEFIT_PATTERNS]
    found = [(benefit, match.group(0)) for benefit, match in matches if match]
    if len(found) != 1:
        return None, None
    return found[0]


def _quoted_field(quote: str) -> dict[str, str]:
    return {"quote": quote}


async def analyze_document(request: AnalyzeRequest) -> dict[str, Any]:
    try:
        content = base64.b64decode(request.content_base64, validate=True)
        document = extract_document(request.filename, content)
    except Exception as exc:
        code = str(exc) if str(exc) in {"EMPTY_FILE", "UNREADABLE_FILE", "UNSUPPORTED_FILE"} else "UNREADABLE_FILE"
        raise HTTPException(status_code=422, detail={"code": code, "message": "Document could not be read"}) from exc
    text = document.text
    benefit, benefit_quote = _find_benefit(text)
    money_matches = list(MONEY_PATTERN.finditer(text))
    distinct_amounts = {match.group(2).replace(",", "") for match in money_matches}
    distinct_currencies = {match.group(1).upper() for match in money_matches}
    amount = next(iter(distinct_amounts)) if len(distinct_amounts) == 1 else None
    currency = next(iter(distinct_currencies)) if len(distinct_currencies) == 1 else None
    reference_matches = list(REFERENCE_PATTERN.finditer(text))
    reference = reference_matches[0].group(1).strip() if len(reference_matches) == 1 else None
    evidence = {
        "benefit": [_quoted_field(benefit_quote)] if benefit_quote else [],
        "amount": [_quoted_field(match.group(0)) for match in money_matches],
        "currency": [_quoted_field(match.group(0)) for match in money_matches],
        "reference": [_quoted_field(match.group(0).strip()) for match in reference_matches],
    }
    review_reasons: list[str] = []
    if benefit is None:
        review_reasons.append("Requested benefit is missing or ambiguous.")
    if amount is None:
        review_reasons.append("Requested amount is missing or ambiguous.")
    if currency is None:
        review_reasons.append("Currency is missing or ambiguous.")
    if reference is None:
        review_reasons.append("Reference is missing or ambiguous.")
    if any(match.group(1).casefold() != request.caller.tenant.casefold() for match in re.finditer(r"\b(Atlas|Boreal)\b", text, re.IGNORECASE)):
        review_reasons.append("Submitted tenant claims are not authoritative; caller lookup controls policy scope.")

    policy: dict[str, Any] | None = None
    if benefit is not None:
        search_term = {
            "certification": "certification reimbursement limit",
            "home-office": "home-office allowance",
            "wellness": "wellness benefit",
            "external-training": "external training approval",
            "rail-travel": "rail travel business trips",
        }[benefit]
        policy = (await answer_question(search_term, request.as_of, request.caller)).model_dump()
        if policy["status"] == "INSUFFICIENT_EVIDENCE":
            review_reasons.append("No applicable policy evidence was found for this benefit.")
        elif policy["status"] == "CONFLICT":
            review_reasons.append("Applicable policy passages conflict and require human resolution.")
    review_reasons.extend([
        "Human review is required for every submission; no claim can be approved from this report.",
        "No claims history is available to establish remaining annual balance.",
        "Policy terms do not establish expense eligibility or a payable amount.",
    ])
    logger.info("document_analyzed", extra={"document_id": request.document_id, "content_type": document.content_type})
    return {
        "extracted": {"benefit": benefit, "amount": amount, "currency": currency, "reference": reference},
        "field_evidence": evidence,
        "policy": policy,
        "review_required": {"required": True, "issues": review_reasons},
    }


@app.post("/analyze")
async def analyze(request: AnalyzeRequest) -> dict[str, Any]:
    return await analyze_document(request)