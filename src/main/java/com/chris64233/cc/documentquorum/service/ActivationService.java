package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.ControlledDocument;
import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.EffectiveVersion;
import com.chris64233.cc.documentquorum.domain.PolicyRequirement;
import com.chris64233.cc.documentquorum.domain.PolicyVersion;
import com.chris64233.cc.documentquorum.domain.PolicyVersionStatus;
import com.chris64233.cc.documentquorum.domain.SignDecision;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.ControlledDocumentRepository;
import com.chris64233.cc.documentquorum.repo.EffectiveVersionRepository;
import com.chris64233.cc.documentquorum.repo.PolicyRequirementRepository;
import com.chris64233.cc.documentquorum.repo.PolicyVersionRepository;
import com.chris64233.cc.documentquorum.repo.SignDecisionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 法定人数评估与生效切换的共享逻辑。
 * 所有方法都假定调用方已持有对应文件版本行的悲观写锁
 * （普通签署、修订投票、修订生效都先锁定同一个 DocumentVersion 行），
 * 因此一个文件版本的进度计算与策略切换永远串行，不会同时按两套策略生效。
 */
@Service
public class ActivationService {

    private final PolicyVersionRepository policyVersionRepo;
    private final PolicyRequirementRepository policyRepo;
    private final SignDecisionRepository decisionRepo;
    private final ControlledDocumentRepository documentRepo;
    private final EffectiveVersionRepository effectiveRepo;

    public ActivationService(PolicyVersionRepository policyVersionRepo,
                             PolicyRequirementRepository policyRepo,
                             SignDecisionRepository decisionRepo,
                             ControlledDocumentRepository documentRepo,
                             EffectiveVersionRepository effectiveRepo) {
        this.policyVersionRepo = policyVersionRepo;
        this.policyRepo = policyRepo;
        this.decisionRepo = decisionRepo;
        this.documentRepo = documentRepo;
        this.effectiveRepo = effectiveRepo;
    }

    /** 当前 ACTIVE 策略版本；正常数据下必存在 */
    public PolicyVersion activePolicy(Long versionId) {
        return policyVersionRepo.findByVersionIdAndStatus(versionId, PolicyVersionStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.POLICY_VERSION_NOT_FOUND,
                        "版本缺少生效中的策略: versionId=" + versionId));
    }

    /** 按当前 ACTIVE 策略版本评估法定人数；只统计未被修订作废的同意 */
    public boolean quorumMet(Long versionId) {
        PolicyVersion active = activePolicy(versionId);
        Map<String, Long> approvals = decisionRepo.findByVersionIdOrderByIdAsc(versionId).stream()
                .filter(d -> d.getDecision() == DecisionType.APPROVE && d.getVoidedPolicyNo() == null)
                .collect(Collectors.groupingBy(SignDecision::getRole, Collectors.counting()));
        List<PolicyRequirement> requirements = policyRepo.findByPolicyVersionId(active.getId());
        return requirements.stream()
                .allMatch(r -> approvals.getOrDefault(r.getRole(), 0L) >= r.getRequiredApprovals());
    }

    /**
     * 让版本生效。若已有更新的文件版本先生效，则本版本不得取代它，
     * 直接置为 SUPERSEDED（法定人数虽达成但已被更新的版本取代）。
     */
    public void activate(DocumentVersion version) {
        ControlledDocument document = documentRepo.findByIdForUpdate(version.getDocument().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文件不存在"));
        Optional<EffectiveVersion> current = effectiveRepo.findById(document.getId());
        if (current.isPresent()) {
            DocumentVersion previous = current.get().getVersion();
            if (previous.getId().equals(version.getId())) {
                version.setStatus(VersionStatus.EFFECTIVE);
                return;
            }
            if (previous.getVersionNo() > version.getVersionNo()) {
                version.setStatus(VersionStatus.SUPERSEDED);
                return;
            }
            previous.setStatus(VersionStatus.SUPERSEDED);
            current.get().setVersion(version);
        } else {
            effectiveRepo.save(new EffectiveVersion(document.getId(), version));
        }
        version.setStatus(VersionStatus.EFFECTIVE);
        version.markEffective(java.time.Instant.now());
    }
}
