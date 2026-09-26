package com.chris64233.cc.documentquorum.service.views;

public record RoleProgressView(String role,
                               int requiredApprovals,
                               long approvedCount,
                               boolean vetoPower,
                               boolean met) {
}
