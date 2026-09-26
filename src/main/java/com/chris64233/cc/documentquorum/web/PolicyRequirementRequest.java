package com.chris64233.cc.documentquorum.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record PolicyRequirementRequest(@NotBlank String role,
                                       @Min(1) int requiredApprovals,
                                       boolean vetoPower) {
}
