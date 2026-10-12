import base64
import json
import sys
from pathlib import Path

from fastapi.testclient import TestClient


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python-service"))
from app.main import app


def main() -> None:
    manifest = json.loads((ROOT / "examples" / "demo-manifest.json").read_text(encoding="utf-8"))
    files_dir = ROOT / "examples" / "requests"
    first_document_by_bytes: dict[bytes, str] = {}
    results = []
    with TestClient(app) as client:
        for entry in manifest["documents"]:
            content = (files_dir / entry["filename"]).read_bytes()
            duplicate_of = first_document_by_bytes.get(content)
            first_document_by_bytes.setdefault(content, entry["document_id"])
            response = client.post("/analyze", json={
                "document_id": entry["document_id"],
                "filename": entry["filename"],
                "content_base64": base64.b64encode(content).decode("ascii"),
                "as_of": manifest["as_of"],
                "caller": {"tenant": "Atlas", "role": "employee"},
            })
            if response.is_success:
                results.append({
                    "document_id": entry["document_id"],
                    "processing_status": "COMPLETED",
                    **response.json(),
                    "duplicate_of": duplicate_of,
                    "error": None,
                })
            else:
                detail = response.json().get("detail", {})
                code = detail.get("code", "DEPENDENCY_FAILURE")
                results.append({
                    "document_id": entry["document_id"],
                    "processing_status": "FAILED",
                    "extracted": None,
                    "field_evidence": None,
                    "policy": None,
                    "review_required": {
                        "required": True,
                        "issues": ["Human review is required; automated processing failed."],
                    },
                    "duplicate_of": duplicate_of,
                    "error": {"code": code, "message": "Document could not be read"},
                })
    completed = sum(result["processing_status"] == "COMPLETED" for result in results)
    output = {
        "batch_id": manifest["batch_id"],
        "summary": {"total": len(results), "completed": completed, "failed": len(results) - completed},
        "results": results,
    }
    response_path = ROOT / "examples" / "responses" / "mixed-batch-response.json"
    response_path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {response_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()