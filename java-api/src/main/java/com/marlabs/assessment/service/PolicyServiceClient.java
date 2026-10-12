package com.marlabs.assessment.service;

import com.marlabs.assessment.api.ApiModels.AnswerResponse;
import com.marlabs.assessment.api.ApiModels.PythonAnalyzeRequest;
import com.marlabs.assessment.api.ApiModels.PythonAnalyzeResponse;
import com.marlabs.assessment.api.ApiModels.PythonAnswerRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PolicyServiceClient {
    private final RestClient restClient;

    public PolicyServiceClient(@Value("${policy-service.base-url}") String baseUrl,
                               @Value("${policy-service.connect-timeout}") Duration connectTimeout,
                               @Value("${policy-service.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public AnswerResponse answer(PythonAnswerRequest request) {
        return restClient.post().uri("/answer").body(request).retrieve().body(AnswerResponse.class);
    }

    public PythonAnalyzeResponse analyze(PythonAnalyzeRequest request) {
        return restClient.post().uri("/analyze").body(request).retrieve().body(PythonAnalyzeResponse.class);
    }
}