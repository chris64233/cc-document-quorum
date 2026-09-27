package com.chris64233.cc.documentquorum.service.views;

import java.time.Instant;
import java.util.List;

public record AmendmentView(String amendmentNo,
                            String status,
                            int basePolicyVersionNo,
                            String amendmentRole,
                            int amendmentThreshold,
                            long approvalCount,
                            Integer enactedPolicyVersionNo,
                            List<PolicyRequirementView> policy,
                            List<AmendmentVoteView> votes,
                            boolean replayed,
                            Instant createdAt) {
}
