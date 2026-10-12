package com.marlabs.assessment.api;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

import static com.marlabs.assessment.api.ApiModels.CallerContext;

@Component
public class CallerRegistry {
    private final Map<String, CallerContext> callers = Map.of(
            "atlas-employee-01", new CallerContext("Atlas", "employee"),
            "atlas-contractor-01", new CallerContext("Atlas", "contractor"),
            "boreal-employee-01", new CallerContext("Boreal", "employee"));

    public Optional<CallerContext> find(String callerId) {
        return Optional.ofNullable(callers.get(callerId));
    }
}