package com.chris64233.cc.documentquorum.web;

import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record UpdateSignerRolesRequest(@NotEmpty Set<String> roles) {
}
