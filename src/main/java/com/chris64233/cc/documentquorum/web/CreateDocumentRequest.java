package com.chris64233.cc.documentquorum.web;

import jakarta.validation.constraints.NotBlank;

public record CreateDocumentRequest(@NotBlank String docCode, @NotBlank String title) {
}
