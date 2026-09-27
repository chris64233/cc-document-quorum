package com.chris64233.cc.documentquorum.service.views;

public record RolePolicyDiffView(String role,
                                 String changeType,
                                 Integer oldRequiredApprovals,
                                 Integer newRequiredApprovals,
                                 Boolean oldVetoPower,
                                 Boolean newVetoPower) {
}
