package com.chris64233.cc.documentquorum.service.views;

import java.util.List;

public record ProgressView(int versionNo, String status, int policyNo, List<RoleProgressView> roles) {
}
