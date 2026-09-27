package com.chris64233.cc.documentquorum.service.views;

import java.util.List;

public record CarryOverView(int versionNo,
                            int currentPolicyVersionNo,
                            List<CarryOverDecisionView> decisions) {
}
