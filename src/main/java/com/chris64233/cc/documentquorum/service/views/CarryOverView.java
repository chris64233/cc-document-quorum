package com.chris64233.cc.documentquorum.service.views;

public record CarryOverView(String eventId,
                            String signerExternalId,
                            String role,
                            String decision,
                            String outcome,
                            String reason) {
}
