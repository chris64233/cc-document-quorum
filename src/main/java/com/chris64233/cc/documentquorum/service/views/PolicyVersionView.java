package com.chris64233.cc.documentquorum.service.views;

import java.time.Instant;
import java.util.List;

public record PolicyVersionView(int policyNo,
                                String status,
                                Instant createdAt,
                                List<PolicyRequirementView> policy) {
}
