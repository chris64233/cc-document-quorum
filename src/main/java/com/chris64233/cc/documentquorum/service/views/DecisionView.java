package com.chris64233.cc.documentquorum.service.views;

import java.time.Instant;

public record DecisionView(String eventId,
                           String signerExternalId,
                           String role,
                           String decision,
                           int policyNo,
                           Integer voidedPolicyNo,
                           Instant createdAt) {
}
