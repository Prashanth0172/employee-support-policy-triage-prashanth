package com.marlabs.assessment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marlabs.assessment.api.ApiModels.AnswerResponse;
import com.marlabs.assessment.api.ApiModels.Citation;
import com.marlabs.assessment.api.ApiModels.PythonAnalyzeResponse;
import com.marlabs.assessment.api.ApiModels.ReviewRequired;
import com.marlabs.assessment.service.PolicyServiceClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SupportControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private PolicyServiceClient policyService;

    @Test
    void rejectsMissingAndUnknownCallers() throws Exception {
        mockMvc.perform(post("/answer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"certification limit\",\"as_of\":\"2026-09-21\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/answer").header("X-Caller-Id", "unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"certification limit\",\"as_of\":\"2026-09-21\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void answersWithConfiguredCallerAndEvidenceContract() throws Exception {
        when(policyService.answer(any())).thenReturn(new AnswerResponse("ANSWERED", "INR 25000 limit", List.of(
                new Citation("atlas-cert-current", "The annual certification reimbursement limit for employees is INR 25000."))));

        mockMvc.perform(post("/answer").header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"certification limit\",\"as_of\":\"2026-09-21\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.citations[0].chunk_id").value("atlas-cert-current"));
    }

    @Test
    void preservesBatchOrderAndReportsExactDuplicate() throws Exception {
        PythonAnalyzeResponse analyzed = new PythonAnalyzeResponse(
                Map.of("benefit", "certification", "amount", "18000", "currency", "INR", "reference", "CERT-101"),
                Map.of("amount", List.of(Map.of("quote", "INR 18000"))),
                new AnswerResponse("ANSWERED", "INR 25000 limit", List.of(new Citation("atlas-cert-current", "The annual certification reimbursement limit for employees is INR 25000."))),
                new ReviewRequired(true, List.of("Human review is required.")));
        when(policyService.analyze(any())).thenReturn(analyzed);
        MockMultipartFile metadata = new MockMultipartFile("metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"batch_id\":\"test\",\"as_of\":\"2026-09-21\",\"documents\":[{\"document_id\":\"one\",\"filename\":\"one.txt\"},{\"document_id\":\"two\",\"filename\":\"two.txt\"}]}".getBytes());
        MockMultipartFile one = new MockMultipartFile("files", "one.txt", "text/plain", "same bytes".getBytes());
        MockMultipartFile two = new MockMultipartFile("files", "two.txt", "text/plain", "same bytes".getBytes());

        mockMvc.perform(multipart("/batches").file(metadata).file(one).file(two)
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(2))
                .andExpect(jsonPath("$.results[0].document_id").value("one"))
                .andExpect(jsonPath("$.results[1].duplicate_of").value("one"));
    }

    @Test
    void continuesBatchAfterAnItemDependencyFailure() throws Exception {
        PythonAnalyzeResponse analyzed = new PythonAnalyzeResponse(
                Map.of("benefit", "certification", "amount", "18000", "currency", "INR", "reference", "CERT-101"),
                Map.of("amount", List.of(Map.of("quote", "INR 18000"))),
                new AnswerResponse("ANSWERED", "INR 25000 limit", List.of(new Citation("atlas-cert-current", "The annual certification reimbursement limit for employees is INR 25000."))),
                new ReviewRequired(true, List.of("Human review is required.")));
        when(policyService.analyze(any())).thenThrow(new ResourceAccessException("offline")).thenReturn(analyzed);
        MockMultipartFile metadata = new MockMultipartFile("metadata", "metadata.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"batch_id\":\"partial\",\"as_of\":\"2026-09-21\",\"documents\":[{\"document_id\":\"bad\",\"filename\":\"bad.txt\"},{\"document_id\":\"good\",\"filename\":\"good.txt\"}]}".getBytes());
        MockMultipartFile bad = new MockMultipartFile("files", "bad.txt", "text/plain", "first".getBytes());
        MockMultipartFile good = new MockMultipartFile("files", "good.txt", "text/plain", "second".getBytes());

        mockMvc.perform(multipart("/batches").file(metadata).file(bad).file(good)
                        .header("X-Caller-Id", "atlas-employee-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.total").value(2))
                .andExpect(jsonPath("$.summary.completed").value(1))
                .andExpect(jsonPath("$.summary.failed").value(1))
                .andExpect(jsonPath("$.results[0].processing_status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].error.code").value("DEPENDENCY_TIMEOUT"))
                .andExpect(jsonPath("$.results[1].processing_status").value("COMPLETED"));
    }

    @Test
    void rejectsInvalidCalendarDate() throws Exception {
        mockMvc.perform(post("/answer").header("X-Caller-Id", "atlas-employee-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"limit\",\"as_of\":\"2026-02-30\"}"))
                .andExpect(status().isBadRequest());
    }
}