package com.chris64233.cc.documentquorum.service.views;

import java.time.Instant;
import java.util.List;

public record AmendmentView(int amendmentNo,
                            int basePolicyNo,
                            int policyNo,
                            String status,
                            long approvalCount,
                            int requiredApprovals,
                            Instant createdAt,
                            Instant effectiveAt,
                            List<PolicyRequirementView> policy,
                            boolean replayed) {
}
