package com.chris64233.cc.documentquorum.service.views;

public record RolePolicyChangeView(String role,
                                   String changeType,
                                   Integer oldRequiredApprovals,
                                   Integer newRequiredApprovals,
                                   Boolean oldVetoPower,
                                   Boolean newVetoPower) {
}
