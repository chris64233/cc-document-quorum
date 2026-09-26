package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.domain.DecisionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubmitDecisionRequest(@NotBlank String eventId,
                                    @NotBlank String signerExternalId,
                                    @NotBlank String role,
                                    @NotNull DecisionType decision) {
}
