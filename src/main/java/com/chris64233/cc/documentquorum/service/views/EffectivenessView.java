package com.chris64233.cc.documentquorum.service.views;

import java.time.Instant;
import java.util.List;

public record EffectivenessView(int versionNo,
                                String status,
                                int effectivePolicyVersionNo,
                                Instant activatedAt,
                                List<PolicyRequirementView> requirements,
                                List<DecisionView> countedDecisions) {
}
