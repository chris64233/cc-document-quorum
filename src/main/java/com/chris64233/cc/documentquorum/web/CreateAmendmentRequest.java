package com.chris64233.cc.documentquorum.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateAmendmentRequest(@NotBlank String amendmentNo,
                                     @NotEmpty List<@Valid PolicyRequirementRequest> policy) {
}
