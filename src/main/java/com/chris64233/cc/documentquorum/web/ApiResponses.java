package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.domain.ControlledDocument;
import com.chris64233.cc.documentquorum.domain.Decision;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.PolicyRoleRequirement;
import com.chris64233.cc.documentquorum.domain.Signer;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import com.chris64233.cc.documentquorum.service.DecisionService;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class ApiResponses {

    private ApiResponses() {
    }

    public record DocumentResponse(Long id, String code, String name) {
        static DocumentResponse from(ControlledDocument document) {
            return new DocumentResponse(document.getId(), document.getCode(), document.getName());
        }
    }

    public record PolicyRequirementResponse(String role, int requiredApprovals, boolean veto) {
        static PolicyRequirementResponse from(PolicyRoleRequirement requirement) {
            return new PolicyRequirementResponse(
                    requirement.getRole(), requirement.getRequiredApprovals(), requirement.isVeto());
        }
    }

    public record VersionResponse(Long id, Long documentId, int versionNumber, String content,
                                  VersionStatus status, Instant createdAt,
                                  List<PolicyRequirementResponse> policy) {
        static VersionResponse from(DocumentVersion version) {
            return new VersionResponse(
                    version.getId(),
                    version.getDocument().getId(),
                    version.getVersionNumber(),
                    version.getContent(),
                    version.getStatus(),
                    version.getCreatedAt(),
                    version.getPolicyRequirements().stream()
                            .map(PolicyRequirementResponse::from)
                            .toList());
        }
    }

    public record SignerResponse(Long id, String externalId, String displayName, Set<String> roles) {
        static SignerResponse from(Signer signer) {
            return new SignerResponse(signer.getId(), signer.getExternalId(),
                    signer.getDisplayName(), signer.getRoles());
        }
    }

    public record DecisionResponse(String eventNo, String signerExternalId, String role,
                                   Decision decision, VersionStatus versionStatus, boolean replayed) {
        static DecisionResponse from(DecisionService.DecisionOutcome outcome) {
            return new DecisionResponse(
                    outcome.eventNo(),
                    outcome.signerExternalId(),
                    outcome.role(),
                    outcome.decision(),
                    outcome.versionStatus(),
                    outcome.replayed());
        }
    }

    public record ApiError(String code, String message) {
    }
}
