package com.marlabs.assessment.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marlabs.assessment.api.ApiModels.AnswerRequest;
import com.marlabs.assessment.api.ApiModels.AnswerResponse;
import com.marlabs.assessment.api.ApiModels.ApiError;
import com.marlabs.assessment.api.ApiModels.BatchResponse;
import com.marlabs.assessment.api.ApiModels.BatchResult;
import com.marlabs.assessment.api.ApiModels.BatchSummary;
import com.marlabs.assessment.api.ApiModels.CallerContext;
import com.marlabs.assessment.api.ApiModels.Manifest;
import com.marlabs.assessment.api.ApiModels.ManifestDocument;
import com.marlabs.assessment.api.ApiModels.PythonAnalyzeRequest;
import com.marlabs.assessment.api.ApiModels.PythonAnalyzeResponse;
import com.marlabs.assessment.api.ApiModels.PythonAnswerRequest;
import com.marlabs.assessment.api.ApiModels.ReviewRequired;
import com.marlabs.assessment.service.PolicyServiceClient;
import jakarta.validation.Valid;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping
@Validated
public class SupportController {
    private static final Logger logger = LoggerFactory.getLogger(SupportController.class);
    private final CallerRegistry callers;
    private final PolicyServiceClient policyService;
    private final ObjectMapper objectMapper;

    public SupportController(CallerRegistry callers, PolicyServiceClient policyService, ObjectMapper objectMapper) {
        this.callers = callers;
        this.policyService = policyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/answer")
    public AnswerResponse answer(@RequestHeader(name = "X-Caller-Id", required = false) String callerId,
                                 @Valid @RequestBody AnswerRequest request) {
        CallerContext caller = requireCaller(callerId);
        requireDate(request.asOf());
        return policyService.answer(new PythonAnswerRequest(request.question(), request.asOf(), caller));
    }

    @PostMapping(value = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BatchResponse> batch(@RequestHeader(name = "X-Caller-Id", required = false) String callerId,
                                               @RequestPart("metadata") String metadata,
                                               @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        CallerContext caller = requireCaller(callerId);
        Manifest manifest = parseManifest(metadata);
        validateManifest(manifest);
        List<MultipartFile> safeFiles = files == null ? List.of() : files;
        Map<String, MultipartFile> filesByName = matchFiles(manifest, safeFiles);

        Map<String, List<SeenUpload>> seenUploadsByDigest = new HashMap<>();
        List<BatchResult> results = new ArrayList<>();
        int completed = 0;
        for (ManifestDocument entry : manifest.documents()) {
            byte[] bytes;
            try {
                bytes = filesByName.get(entry.filename()).getBytes();
            } catch (IOException | RuntimeException exception) {
                results.add(failed(entry.document_id(), null, "UNREADABLE_FILE", "Document could not be read"));
                logger.warn("batch_item_failed batch_id={} document_id={} unreadable=true", manifest.batch_id(), entry.document_id());
                continue;
            }
            String digest = sha256(bytes);
            List<SeenUpload> sameDigestUploads = seenUploadsByDigest.computeIfAbsent(digest, ignored -> new ArrayList<>());
            String duplicateOf = sameDigestUploads.stream()
                    .filter(upload -> Arrays.equals(upload.bytes(), bytes))
                    .map(SeenUpload::documentId)
                    .findFirst()
                    .orElse(null);
            if (duplicateOf == null) {
                sameDigestUploads.add(new SeenUpload(entry.document_id(), bytes));
            }
            try {
                PythonAnalyzeResponse response = policyService.analyze(new PythonAnalyzeRequest(
                        entry.document_id(), entry.filename(), Base64.getEncoder().encodeToString(bytes), manifest.asOf(), caller));
                results.add(new BatchResult(entry.document_id(), "COMPLETED", response.extracted(), response.field_evidence(),
                        response.policy(), response.review_required(), duplicateOf, null));
                completed++;
                logger.info("batch_item_completed batch_id={} document_id={} duplicate={}", manifest.batch_id(), entry.document_id(), duplicateOf != null);
            } catch (HttpStatusCodeException exception) {
                int statusCode = exception.getStatusCode().value();
                String code = statusCode == 422 ? "UNREADABLE_FILE" : statusCode == 504 ? "DEPENDENCY_TIMEOUT" : "DEPENDENCY_FAILURE";
                String message = code.equals("UNREADABLE_FILE") ? "Document could not be read"
                    : code.equals("DEPENDENCY_TIMEOUT") ? "Policy service timed out" : "Policy service failed";
                results.add(failed(entry.document_id(), duplicateOf, code, message));
                logger.warn("batch_item_failed batch_id={} document_id={} dependency_status={}", manifest.batch_id(), entry.document_id(), exception.getStatusCode().value());
            } catch (ResourceAccessException exception) {
                results.add(failed(entry.document_id(), duplicateOf, "DEPENDENCY_TIMEOUT", "Policy service timed out"));
                logger.warn("batch_item_failed batch_id={} document_id={} dependency_timeout=true", manifest.batch_id(), entry.document_id());
            } catch (RuntimeException exception) {
                results.add(failed(entry.document_id(), duplicateOf, "DEPENDENCY_FAILURE", "Policy service failed"));
                logger.warn("batch_item_failed batch_id={} document_id={} dependency_failure=true", manifest.batch_id(), entry.document_id());
            }
        }
        return ResponseEntity.ok(new BatchResponse(manifest.batch_id(),
                new BatchSummary(results.size(), completed, results.size() - completed), results));
    }

    private CallerContext requireCaller(String callerId) {
        if (!StringUtils.hasText(callerId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Caller identity required");
        }
        return callers.find(callerId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown caller"));
    }

    private Manifest parseManifest(String metadata) {
        try {
            return objectMapper.readValue(metadata, Manifest.class);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid metadata JSON");
        }
    }

    private void validateManifest(Manifest manifest) {
        if (manifest == null || !StringUtils.hasText(manifest.batch_id()) || manifest.documents() == null || manifest.documents().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid manifest");
        }
        requireDate(manifest.asOf());
        Set<String> ids = new HashSet<>();
        Set<String> filenames = new HashSet<>();
        for (ManifestDocument entry : manifest.documents()) {
            if (entry == null || !StringUtils.hasText(entry.document_id()) || !StringUtils.hasText(entry.filename())
                    || !ids.add(entry.document_id()) || !filenames.add(entry.filename())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate or invalid manifest entry");
            }
        }
    }

    private void requireDate(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "as_of must use YYYY-MM-DD");
        }
        try {
            LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "as_of must be a valid date");
        }
    }

    private Map<String, MultipartFile> matchFiles(Manifest manifest, List<MultipartFile> files) {
        Map<String, MultipartFile> byName = new HashMap<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (!StringUtils.hasText(filename) || byName.putIfAbsent(filename, file) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate or missing uploaded filename");
            }
        }
        Set<String> expected = manifest.documents().stream().map(ManifestDocument::filename).collect(java.util.stream.Collectors.toSet());
        if (!byName.keySet().equals(expected)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded files do not match manifest");
        }
        return byName;
    }

    private BatchResult failed(String documentId, String duplicateOf, String code, String message) {
        return new BatchResult(documentId, "FAILED", null, null, null,
                new ReviewRequired(true, List.of("Human review is required; automated processing failed.")), duplicateOf,
                new ApiError(code, message));
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record SeenUpload(String documentId, byte[] bytes) {}
}