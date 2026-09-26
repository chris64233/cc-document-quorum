package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.ControlledDocument;
import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.EffectiveVersion;
import com.chris64233.cc.documentquorum.domain.PolicyRequirement;
import com.chris64233.cc.documentquorum.domain.SignDecision;
import com.chris64233.cc.documentquorum.domain.Signer;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.ControlledDocumentRepository;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import com.chris64233.cc.documentquorum.repo.EffectiveVersionRepository;
import com.chris64233.cc.documentquorum.repo.PolicyRequirementRepository;
import com.chris64233.cc.documentquorum.repo.SignDecisionRepository;
import com.chris64233.cc.documentquorum.repo.SignerRepository;
import com.chris64233.cc.documentquorum.service.views.DecisionResultView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SigningService {

    private final ControlledDocumentRepository documentRepo;
    private final DocumentVersionRepository versionRepo;
    private final PolicyRequirementRepository policyRepo;
    private final SignerRepository signerRepo;
    private final SignDecisionRepository decisionRepo;
    private final EffectiveVersionRepository effectiveRepo;

    public SigningService(ControlledDocumentRepository documentRepo,
                          DocumentVersionRepository versionRepo,
                          PolicyRequirementRepository policyRepo,
                          SignerRepository signerRepo,
                          SignDecisionRepository decisionRepo,
                          EffectiveVersionRepository effectiveRepo) {
        this.documentRepo = documentRepo;
        this.versionRepo = versionRepo;
        this.policyRepo = policyRepo;
        this.signerRepo = signerRepo;
        this.decisionRepo = decisionRepo;
        this.effectiveRepo = effectiveRepo;
    }

    @Transactional
    public DecisionResultView submit(String docCode, int versionNo, String eventId,
                                     String signerExternalId, String role, DecisionType decision) {
        Optional<SignDecision> replay = decisionRepo.findByEventId(eventId);
        if (replay.isPresent()) {
            return replayOrConflict(replay.get(), docCode, versionNo, signerExternalId, role, decision);
        }

        DocumentVersion version = versionRepo.findByDocCodeAndVersionNoForUpdate(docCode, versionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: " + docCode + " v" + versionNo));

        Optional<SignDecision> replayUnderLock = decisionRepo.findByEventId(eventId);
        if (replayUnderLock.isPresent()) {
            return replayOrConflict(replayUnderLock.get(), docCode, versionNo, signerExternalId, role, decision);
        }

        if (version.getStatus() != VersionStatus.PENDING) {
            throw new BusinessException(ErrorCode.TERMINAL_STATE,
                    "版本已处于终态 " + version.getStatus() + "，不再接受新决定");
        }

        Signer signer = signerRepo.findByExternalId(signerExternalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNER_NOT_FOUND,
                        "签署人不存在: " + signerExternalId));

        PolicyRequirement requirement = policyRepo.findByVersionIdAndRole(version.getId(), role)
                .orElseThrow(() -> new BusinessException(ErrorCode.ELIGIBILITY,
                        "角色 " + role + " 不在该版本签署策略中"));
        if (!signer.getRoles().contains(role)) {
            throw new BusinessException(ErrorCode.ELIGIBILITY,
                    "签署人 " + signerExternalId + " 不具备角色 " + role);
        }

        if (decisionRepo.existsByVersionIdAndSignerId(version.getId(), signer.getId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_DECISION,
                    "签署人 " + signerExternalId + " 已对该版本作出过决定");
        }

        decisionRepo.saveAndFlush(new SignDecision(version, signer, role, decision, eventId));

        if (decision == DecisionType.REJECT && requirement.isVetoPower()) {
            version.setStatus(VersionStatus.REJECTED);
        } else if (decision == DecisionType.APPROVE && quorumMet(version.getId())) {
            activate(version);
        }

        return new DecisionResultView(eventId, version.getVersionNo(), version.getStatus().name(), false);
    }

    private DecisionResultView replayOrConflict(SignDecision existing, String docCode, int versionNo,
                                                String signerExternalId, String role, DecisionType decision) {
        DocumentVersion existingVersion = existing.getVersion();
        boolean sameContent = existingVersion.getDocument().getDocCode().equals(docCode)
                && existingVersion.getVersionNo() == versionNo
                && existing.getSigner().getExternalId().equals(signerExternalId)
                && existing.getRole().equals(role)
                && existing.getDecision() == decision;
        if (!sameContent) {
            throw new BusinessException(ErrorCode.EVENT_CONFLICT,
                    "事件号 " + existing.getEventId() + " 已存在且内容不同");
        }
        return new DecisionResultView(existing.getEventId(), existingVersion.getVersionNo(),
                existingVersion.getStatus().name(), true);
    }

    private boolean quorumMet(Long versionId) {
        Map<String, Long> approvals = decisionRepo.findByVersionIdOrderByIdAsc(versionId).stream()
                .filter(d -> d.getDecision() == DecisionType.APPROVE)
                .collect(Collectors.groupingBy(SignDecision::getRole, Collectors.counting()));
        List<PolicyRequirement> requirements = policyRepo.findByVersionId(versionId);
        return requirements.stream()
                .allMatch(r -> approvals.getOrDefault(r.getRole(), 0L) >= r.getRequiredApprovals());
    }

    private void activate(DocumentVersion version) {
        ControlledDocument document = documentRepo.findByIdForUpdate(version.getDocument().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文件不存在"));
        Optional<EffectiveVersion> current = effectiveRepo.findById(document.getId());
        if (current.isPresent()) {
            DocumentVersion previous = current.get().getVersion();
            if (!previous.getId().equals(version.getId())) {
                previous.setStatus(VersionStatus.SUPERSEDED);
                current.get().setVersion(version);
            }
        } else {
            effectiveRepo.save(new EffectiveVersion(document.getId(), version));
        }
        version.setStatus(VersionStatus.EFFECTIVE);
    }
}
