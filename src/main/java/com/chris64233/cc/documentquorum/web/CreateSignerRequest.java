package com.chris64233.cc.documentquorum.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record CreateSignerRequest(@NotBlank String externalId,
                                  @NotBlank String name,
                                  @NotEmpty Set<String> roles) {
}
