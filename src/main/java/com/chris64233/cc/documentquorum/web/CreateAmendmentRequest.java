package com.chris64233.cc.documentquorum.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateAmendmentRequest(@NotNull @Min(1) Integer amendmentNo,
                                     @NotEmpty List<@Valid PolicyRequirementRequest> policy) {
}
