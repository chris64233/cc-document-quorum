package com.chris64233.cc.documentquorum.service.views;

import java.time.Instant;

public record AmendmentVoteView(String eventId,
                                String signerExternalId,
                                String decision,
                                Instant createdAt) {
}
