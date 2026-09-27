package com.chris64233.cc.documentquorum.service.views;

import java.util.List;

public record PolicyDiffView(int versionNo,
                             int fromPolicyVersionNo,
                             int toPolicyVersionNo,
                             List<RolePolicyDiffView> changes) {
}
