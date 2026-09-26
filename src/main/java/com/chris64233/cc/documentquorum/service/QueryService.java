package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.Decision;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.PolicyRoleRequirement;
import com.chris64233.cc.documentquorum.domain.SignEvent;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import com.chris64233.cc.documentquorum.repo.SignEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class QueryService {

    private final DocumentVersionRepository versionRepository;
    private final SignEventRepository signEventRepository;

    public QueryService(DocumentVersionRepository versionRepository,
                        SignEventRepository signEventRepository) {
        this.versionRepository = versionRepository;
        this.signEventRepository = signEventRepository;
    }

    @Transactional(readOnly = true)
    public ProgressView progress(Long versionId) {
        DocumentVersion version = loadVersion(versionId);
        Map<String, Long> approvals = countByRole(versionId, Decision.APPROVE);
        Map<String, Long> rejections = countByRole(versionId, Decision.REJECT);
        List<RoleProgress> roles = new ArrayList<>();
        for (PolicyRoleRequirement requirement : version.getPolicyRequirements()) {
            long approved = approvals.getOrDefault(requirement.getRole(), 0L);
            roles.add(new RoleProgress(
                    requirement.getRole(),
                    requirement.getRequiredApprovals(),
                    approved,
                    approved >= requirement.getRequiredApprovals(),
                    requirement.isVeto(),
                    rejections.getOrDefault(requirement.getRole(), 0L)));
        }
        return new ProgressView(version.getId(), version.getVersionNumber(), version.getStatus(), roles);
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> audit(Long versionId) {
        DocumentVersion version = loadVersion(versionId);
        return signEventRepository.findByVersionIdOrderByIdAsc(version.getId()).stream()
                .map(event -> new AuditEntry(
                        event.getEventNo(),
                        event.getSigner().getExternalId(),
                        event.getRole(),
                        event.getDecision(),
                        event.getOccurredAt()))
                .toList();
    }

    private DocumentVersion loadVersion(Long versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "版本不存在: " + versionId));
    }

    private Map<String, Long> countByRole(Long versionId, Decision decision) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : signEventRepository.countByVersionAndDecisionGroupByRole(versionId, decision)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    public record RoleProgress(String role, int requiredApprovals, long approvedCount,
                               boolean thresholdMet, boolean veto, long rejectedCount) {
    }

    public record ProgressView(Long versionId, int versionNumber, VersionStatus status,
                               List<RoleProgress> roles) {
    }

    public record AuditEntry(String eventNo, String signerExternalId, String role,
                             Decision decision, Instant occurredAt) {
    }
}
