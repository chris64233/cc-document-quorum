package com.chris64233.cc.documentquorum.service.views;

import java.time.Instant;

public record EffectiveBasisView(String docCode,
                                 int versionNo,
                                 String versionStatus,
                                 int policyNo,
                                 String basis,
                                 Integer amendmentNo,
                                 Instant effectiveAt) {
}
