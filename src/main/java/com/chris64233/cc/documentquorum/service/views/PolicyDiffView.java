package com.chris64233.cc.documentquorum.service.views;

import java.util.List;

public record PolicyDiffView(int fromPolicyNo,
                             int toPolicyNo,
                             List<RolePolicyChangeView> changes) {
}
