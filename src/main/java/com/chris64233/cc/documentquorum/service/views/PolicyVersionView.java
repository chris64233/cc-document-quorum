package com.chris64233.cc.documentquorum.service.views;

import java.util.List;

public record PolicyVersionView(int policyVersionNo,
                                boolean active,
                                String amendmentRole,
                                Integer amendmentThreshold,
                                String sourceAmendmentNo,
                                List<PolicyRequirementView> requirements) {
}
