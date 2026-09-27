package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.AmendmentRequirement;
import com.chris64233.cc.documentquorum.domain.AmendmentStatus;
import com.chris64233.cc.documentquorum.domain.AmendmentVote;
import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.PolicyAmendment;
import com.chris64233.cc.documentquorum.domain.PolicyVersionMeta;
import com.chris64233.cc.documentquorum.domain.SignDecision;
import com.chris64233.cc.documentquorum.domain.Signer;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.AmendmentRequirementRepository;
import com.chris64233.cc.documentquorum.repo.AmendmentVoteRepository;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import com.chris64233.cc.documentquorum.repo.PolicyAmendmentRepository;
import com.chris64233.cc.documentquorum.repo.PolicyRequirementRepository;
import com.chris64233.cc.documentquorum.repo.PolicyVersionMetaRepository;
import com.chris64233.cc.documentquorum.repo.SignDecisionRepository;
import com.chris64233.cc.documentquorum.repo.SignerRepository;
import com.chris64233.cc.documentquorum.service.views.AmendmentView;
import com.chris64233.cc.documentquorum.service.views.AmendmentVoteResultView;
import com.chris64233.cc.documentquorum.service.views.AmendmentVoteView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 策略修订：创建时冻结基线策略版本、管理角色与修订门槛；管理角色投票
 * 达到修订门槛后在版本行悲观锁内生效，重算决定沿用并按新策略重算法定人数。
 */
@Service
public class AmendmentService {

    private final DocumentVersionRepository versionRepo;
    private final PolicyAmendmentRepository amendmentRepo;
    private final AmendmentRequirementRepository amendmentRequirementRepo;
    private final AmendmentVoteRepository voteRepo;
    private final PolicyVersionMetaRepository policyMetaRepo;
    private final PolicyRequirementRepository policyRepo;
    private final SignDecisionRepository decisionRepo;
    private final SignerRepository signerRepo;
    private final SigningService signingService;

    public AmendmentService(DocumentVersionRepository versionRepo,
                            PolicyAmendmentRepository amendmentRepo,
                            AmendmentRequirementRepository amendmentRequirementRepo,
                            AmendmentVoteRepository voteRepo,
                            PolicyVersionMetaRepository policyMetaRepo,
                            PolicyRequirementRepository policyRepo,
                            SignDecisionRepository decisionRepo,
                            SignerRepository signerRepo,
                            SigningService signingService) {
        this.versionRepo = versionRepo;
        this.amendmentRepo = amendmentRepo;
        this.amendmentRequirementRepo = amendmentRequirementRepo;
        this.voteRepo = voteRepo;
        this.policyMetaRepo = policyMetaRepo;
        this.policyRepo = policyRepo;
        this.decisionRepo = decisionRepo;
        this.signerRepo = signerRepo;
        this.signingService = signingService;
    }

    @Transactional
    public AmendmentView createAmendment(String docCode, int versionNo, String amendmentNo,
                                         List<PolicyRequirementView> policy) {
        if (amendmentNo == null || amendmentNo.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "修订号不能为空");
        }
        DocumentService.validatePolicy(policy);

        DocumentVersion version = findVersion(docCode, versionNo);
        Optional<PolicyAmendment> existing = amendmentRepo.findByVersionIdAndAmendmentNo(
                version.getId(), amendmentNo);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), policy);
        }

        version = lockVersion(docCode, versionNo);
        existing = amendmentRepo.findByVersionIdAndAmendmentNo(version.getId(), amendmentNo);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), policy);
        }

        if (version.getStatus() != VersionStatus.PENDING) {
            throw new BusinessException(ErrorCode.TERMINAL_STATE,
                    "版本已处于终态 " + version.getStatus()
                            + "，不能修订策略；已被明确拒绝的版本不能通过降低门槛重新生效");
        }

        PolicyVersionMeta currentMeta = policyMetaRepo
                .findByVersionIdAndPolicyVersionNo(version.getId(), version.getCurrentPolicyVersionNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.POLICY_VERSION_NOT_FOUND, "当前策略版本缺失"));
        if (currentMeta.getAmendmentRole() == null) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "原策略未指定管理角色，无法创建修订");
        }

        PolicyAmendment amendment = amendmentRepo.save(new PolicyAmendment(version, amendmentNo,
                version.getCurrentPolicyVersionNo(), currentMeta.getAmendmentRole(),
                currentMeta.getAmendmentThreshold()));
        for (PolicyRequirementView p : policy) {
            amendmentRequirementRepo.save(new AmendmentRequirement(
                    amendment, p.role(), p.requiredApprovals(), p.vetoPower()));
        }
        return toView(amendment, false);
    }

    @Transactional
    public AmendmentVoteResultView vote(String docCode, int versionNo, String amendmentNo,
                                        String eventId, String signerExternalId, DecisionType decision) {
        Optional<AmendmentVote> replay = voteRepo.findByEventId(eventId);
        if (replay.isPresent()) {
            return replayVoteOrConflict(replay.get(), docCode, versionNo, amendmentNo,
                    signerExternalId, decision);
        }

        DocumentVersion version = lockVersion(docCode, versionNo);

        Optional<AmendmentVote> replayUnderLock = voteRepo.findByEventId(eventId);
        if (replayUnderLock.isPresent()) {
            return replayVoteOrConflict(replayUnderLock.get(), docCode, versionNo, amendmentNo,
                    signerExternalId, decision);
        }

        if (version.getStatus() != VersionStatus.PENDING) {
            throw new BusinessException(ErrorCode.TERMINAL_STATE,
                    "版本已处于终态 " + version.getStatus() + "，不再接受修订投票");
        }

        PolicyAmendment amendment = amendmentRepo.findByVersionIdAndAmendmentNo(version.getId(), amendmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.AMENDMENT_NOT_FOUND,
                        "修订不存在: " + amendmentNo));

        if (effectiveStatus(amendment) != AmendmentStatus.PENDING) {
            throw new BusinessException(ErrorCode.TERMINAL_STATE,
                    "修订已处于终态 " + effectiveStatus(amendment) + "，不再接受投票");
        }

        Signer signer = signerRepo.findByExternalId(signerExternalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNER_NOT_FOUND,
                        "签署人不存在: " + signerExternalId));
        if (!signer.getRoles().contains(amendment.getAmendmentRole())) {
            throw new BusinessException(ErrorCode.ELIGIBILITY,
                    "签署人 " + signerExternalId + " 不具备管理角色 " + amendment.getAmendmentRole());
        }

        if (voteRepo.existsByAmendmentIdAndSignerId(amendment.getId(), signer.getId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_DECISION,
                    "签署人 " + signerExternalId + " 已对该修订投过票");
        }

        voteRepo.saveAndFlush(new AmendmentVote(amendment, signer, decision, eventId));

        if (decision == DecisionType.APPROVE && approvalCount(amendment) >= amendment.getAmendmentThreshold()) {
            enact(version, amendment);
        }

        return new AmendmentVoteResultView(eventId, amendmentNo, effectiveStatus(amendment).name(),
                version.getStatus().name(), false);
    }

    @Transactional(readOnly = true)
    public List<AmendmentView> listAmendments(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        return amendmentRepo.findByVersionIdOrderByIdAsc(version.getId()).stream()
                .map(a -> toView(a, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public AmendmentView getAmendment(String docCode, int versionNo, String amendmentNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        PolicyAmendment amendment = amendmentRepo.findByVersionIdAndAmendmentNo(version.getId(), amendmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.AMENDMENT_NOT_FOUND,
                        "修订不存在: " + amendmentNo));
        return toView(amendment, false);
    }

    /**
     * 修订生效：生成下一代策略版本，重算已有决定的沿用标记，
     * 再按新策略重算法定人数（可能直接使版本生效）。调用方持有版本行悲观锁。
     */
    private void enact(DocumentVersion version, PolicyAmendment amendment) {
        int newPolicyVersionNo = version.getCurrentPolicyVersionNo() + 1;
        policyMetaRepo.save(new PolicyVersionMeta(version, newPolicyVersionNo,
                amendment.getAmendmentRole(), amendment.getAmendmentThreshold(),
                amendment.getAmendmentNo()));
        List<AmendmentRequirement> proposed = amendmentRequirementRepo.findByAmendmentId(amendment.getId());
        for (AmendmentRequirement p : proposed) {
            policyRepo.save(new com.chris64233.cc.documentquorum.domain.PolicyRequirement(
                    version, newPolicyVersionNo, p.getRole(), p.getRequiredApprovals(), p.isVetoPower()));
        }
        version.setCurrentPolicyVersionNo(newPolicyVersionNo);

        // 同版本其余未决修订的基线策略已被取代，标记为 STALE（终态）
        for (PolicyAmendment other : amendmentRepo.findByVersionIdOrderByIdAsc(version.getId())) {
            if (!other.getId().equals(amendment.getId()) && other.getStatus() == AmendmentStatus.PENDING) {
                other.setStatus(AmendmentStatus.STALE);
            }
        }

        Set<String> newRoles = proposed.stream()
                .map(AmendmentRequirement::getRole)
                .collect(Collectors.toSet());
        for (SignDecision decision : decisionRepo.findByVersionIdOrderByIdAsc(version.getId())) {
            boolean carried = decision.getDecision() == DecisionType.APPROVE
                    && newRoles.contains(decision.getRole())
                    && decision.getSigner().getRoles().contains(decision.getRole());
            decision.setCounted(carried);
        }

        amendment.setStatus(AmendmentStatus.ENACTED);
        amendment.setEnactedPolicyVersionNo(newPolicyVersionNo);

        if (signingService.quorumMet(version.getId(), newPolicyVersionNo)) {
            signingService.activate(version);
        }
    }

    private AmendmentView replayOrConflict(PolicyAmendment existing, List<PolicyRequirementView> policy) {
        Set<PolicyRequirementView> proposed = amendmentRequirementRepo.findByAmendmentId(existing.getId())
                .stream()
                .map(r -> new PolicyRequirementView(r.getRole(), r.getRequiredApprovals(), r.isVetoPower()))
                .collect(Collectors.toSet());
        if (!proposed.equals(Set.copyOf(policy))) {
            throw new BusinessException(ErrorCode.EVENT_CONFLICT,
                    "修订号 " + existing.getAmendmentNo() + " 已存在且内容不同");
        }
        return toView(existing, true);
    }

    private AmendmentVoteResultView replayVoteOrConflict(AmendmentVote existing, String docCode,
                                                         int versionNo, String amendmentNo,
                                                         String signerExternalId, DecisionType decision) {
        PolicyAmendment amendment = existing.getAmendment();
        DocumentVersion version = amendment.getVersion();
        boolean sameContent = version.getDocument().getDocCode().equals(docCode)
                && version.getVersionNo() == versionNo
                && amendment.getAmendmentNo().equals(amendmentNo)
                && existing.getSigner().getExternalId().equals(signerExternalId)
                && existing.getDecision() == decision;
        if (!sameContent) {
            throw new BusinessException(ErrorCode.EVENT_CONFLICT,
                    "事件号 " + existing.getEventId() + " 已存在且内容不同");
        }
        return new AmendmentVoteResultView(existing.getEventId(), amendment.getAmendmentNo(),
                effectiveStatus(amendment).name(), version.getStatus().name(), true);
    }

    /**
     * 修订的展示状态：PENDING 但基线策略已被取代的修订视为 STALE
     * （生效时会持久化其余修订为 STALE，此处兜底保证读取一致）。
     */
    private AmendmentStatus effectiveStatus(PolicyAmendment amendment) {
        if (amendment.getStatus() == AmendmentStatus.PENDING
                && amendment.getBasePolicyVersionNo()
                        != amendment.getVersion().getCurrentPolicyVersionNo()) {
            return AmendmentStatus.STALE;
        }
        return amendment.getStatus();
    }

    private long approvalCount(PolicyAmendment amendment) {
        return voteRepo.findByAmendmentIdOrderByIdAsc(amendment.getId()).stream()
                .filter(v -> v.getDecision() == DecisionType.APPROVE)
                .count();
    }

    private AmendmentView toView(PolicyAmendment amendment, boolean replayed) {
        List<PolicyRequirementView> policy = amendmentRequirementRepo.findByAmendmentId(amendment.getId())
                .stream()
                .sorted(Comparator.comparing(AmendmentRequirement::getRole))
                .map(r -> new PolicyRequirementView(r.getRole(), r.getRequiredApprovals(), r.isVetoPower()))
                .toList();
        List<AmendmentVoteView> votes = voteRepo.findByAmendmentIdOrderByIdAsc(amendment.getId())
                .stream()
                .map(v -> new AmendmentVoteView(v.getEventId(), v.getSigner().getExternalId(),
                        v.getDecision().name(), v.getCreatedAt()))
                .toList();
        return new AmendmentView(amendment.getAmendmentNo(), effectiveStatus(amendment).name(),
                amendment.getBasePolicyVersionNo(), amendment.getAmendmentRole(),
                amendment.getAmendmentThreshold(), approvalCount(amendment),
                amendment.getEnactedPolicyVersionNo(), policy, votes, replayed, amendment.getCreatedAt());
    }

    private DocumentVersion findVersion(String docCode, int versionNo) {
        return versionRepo.findByDocumentDocCodeAndVersionNo(docCode, versionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: " + docCode + " v" + versionNo));
    }

    private DocumentVersion lockVersion(String docCode, int versionNo) {
        return versionRepo.findByDocCodeAndVersionNoForUpdate(docCode, versionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: " + docCode + " v" + versionNo));
    }
}
