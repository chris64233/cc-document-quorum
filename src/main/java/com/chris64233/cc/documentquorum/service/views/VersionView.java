package com.chris64233.cc.documentquorum.service.views;

import java.time.Instant;
import java.util.List;

public record VersionView(Long id,
                          int versionNo,
                          String status,
                          String content,
                          Instant createdAt,
                          List<PolicyRequirementView> policy,
                          int activePolicyNo,
                          String amendRole,
                          Integer amendRequiredApprovals) {
}
