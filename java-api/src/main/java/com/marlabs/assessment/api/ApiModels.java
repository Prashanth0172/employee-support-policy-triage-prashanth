package com.marlabs.assessment.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {}

    public record CallerContext(String tenant, String role) {}

    public record AnswerRequest(
            @NotBlank String question,
            @JsonProperty("as_of") @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String asOf) {}

    public record Citation(String chunk_id, String quote) {}

    public record AnswerResponse(String status, String answer, List<Citation> citations) {}

    public record Manifest(String batch_id,
                           @JsonProperty("as_of") String asOf,
                           List<ManifestDocument> documents) {}

    public record ManifestDocument(String document_id, String filename) {}

    public record ReviewRequired(boolean required, List<String> issues) {}

    public record ApiError(String code, String message) {}

    public record BatchResult(String document_id,
                              String processing_status,
                              Map<String, Object> extracted,
                              Map<String, Object> field_evidence,
                              AnswerResponse policy,
                              ReviewRequired review_required,
                              String duplicate_of,
                              ApiError error) {}

    public record BatchSummary(int total, int completed, int failed) {}

    public record BatchResponse(String batch_id, BatchSummary summary, List<BatchResult> results) {}

    public record PythonAnswerRequest(String question, String as_of, CallerContext caller) {}

    public record PythonAnalyzeRequest(String document_id,
                                       String filename,
                                       String content_base64,
                                       String as_of,
                                       CallerContext caller) {}

    public record PythonAnalyzeResponse(Map<String, Object> extracted,
                                        Map<String, Object> field_evidence,
                                        AnswerResponse policy,
                                        ReviewRequired review_required) {}
}