package com.chris64233.cc.documentquorum.service.views;

public record CarryOverDecisionView(String eventId,
                                    String signerExternalId,
                                    String role,
                                    String decision,
                                    int policyVersionNo,
                                    boolean counted,
                                    String reason) {
}
