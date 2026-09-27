package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.AmendmentDecision;
import com.chris64233.cc.documentquorum.domain.AmendmentStatus;
import com.chris64233.cc.documentquorum.domain.CarryOverOutcome;
import com.chris64233.cc.documentquorum.domain.CarryOverRecord;
import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.PolicyAmendment;
import com.chris64233.cc.documentquorum.domain.PolicyRequirement;
import com.chris64233.cc.documentquorum.domain.PolicyVersion;
import com.chris64233.cc.documentquorum.domain.PolicyVersionStatus;
import com.chris64233.cc.documentquorum.domain.SignDecision;
import com.chris64233.cc.documentquorum.domain.Signer;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.AmendmentDecisionRepository;
import com.chris64233.cc.documentquorum.repo.CarryOverRecordRepository;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import com.chris64233.cc.documentquorum.repo.PolicyAmendmentRepository;
import com.chris64233.cc.documentquorum.repo.PolicyRequirementRepository;
import com.chris64233.cc.documentquorum.repo.PolicyVersionRepository;
import com.chris64233.cc.documentquorum.repo.SignDecisionRepository;
import com.chris64233.cc.documentquorum.repo.SignerRepository;
import com.chris64233.cc.documentquorum.service.views.AmendmentResultView;
import com.chris64233.cc.documentquorum.service.views.AmendmentView;
import com.chris64233.cc.documentquorum.service.views.CarryOverView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AmendmentService {

    private final DocumentVersionRepository versionRepo;
    private final PolicyVersionRepository policyVersionRepo;
    private final PolicyRequirementRepository policyRepo;
    private final PolicyAmendmentRepository amendmentRepo;
    private final AmendmentDecisionRepository amendmentDecisionRepo;
    private final CarryOverRecordRepository carryOverRepo;
    private final SignDecisionRepository decisionRepo;
    private final SignerRepository signerRepo;
    private final ActivationService activationService;

    public AmendmentService(DocumentVersionRepository versionRepo,
                            PolicyVersionRepository policyVersionRepo,
                            PolicyRequirementRepository policyRepo,
                            PolicyAmendmentRepository amendmentRepo,
                            AmendmentDecisionRepository amendmentDecisionRepo,
                            CarryOverRecordRepository carryOverRepo,
                            SignDecisionRepository decisionRepo,
                            SignerRepository signerRepo,
                            ActivationService activationService) {
        this.versionRepo = versionRepo;
        this.policyVersionRepo = policyVersionRepo;
        this.policyRepo = policyRepo;
        this.amendmentRepo = amendmentRepo;
        this.amendmentDecisionRepo = amendmentDecisionRepo;
        this.carryOverRepo = carryOverRepo;
        this.decisionRepo = decisionRepo;
        this.signerRepo = signerRepo;
        this.activationService = activationService;
    }

    /**
     * 创建策略修订：冻结当前文件版本与当前 ACTIVE 策略版本号，
     * 新策略作为 PENDING 策略版本保存，等待管理角色投票。
     * amendmentNo 在文件版本内唯一，作为幂等键。
     */
    @Transactional
    public AmendmentView createAmendment(String docCode, int versionNo, int amendmentNo,
                                         List<PolicyRequirementView> policy) {
        validatePolicy(policy);
        DocumentVersion version = lockVersion(docCode, versionNo);

        Optional<PolicyAmendment> existing = amendmentRepo.findByVersionIdAndAmendmentNo(version.getId(), amendmentNo);
        if (existing.isPresent()) {
            PolicyAmendment amendment = existing.get();
            if (!samePolicy(policy, policyRepo.findByPolicyVersionId(amendment.getPolicyVersion().getId()))) {
                throw new BusinessException(ErrorCode.AMENDMENT_CONFLICT,
                        "修订号 " + amendmentNo + " 已存在且策略内容不同");
            }
            return toAmendmentView(amendment, true);
        }

        if (version.getStatus() != VersionStatus.PENDING) {
            throw new BusinessException(ErrorCode.TERMINAL_STATE,
                    "版本已处于终态 " + version.getStatus() + "，不能修订策略（已被拒绝的版本不能通过降低门槛生效）");
        }
        if (version.getAmendRole() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "该版本策略未指定修订管理角色，不允许修订");
        }

        PolicyVersion base = activationService.activePolicy(version.getId());
        int newPolicyNo = policyVersionRepo.findMaxPolicyNoByVersionId(version.getId()).orElse(0) + 1;
        PolicyVersion newPolicy = policyVersionRepo.save(
                new PolicyVersion(version, newPolicyNo, PolicyVersionStatus.PENDING));
        for (PolicyRequirementView p : policy) {
            policyRepo.save(new PolicyRequirement(newPolicy, p.role(), p.requiredApprovals(), p.vetoPower()));
        }
        PolicyAmendment amendment = amendmentRepo.save(
                new PolicyAmendment(version, amendmentNo, base.getPolicyNo(), newPolicy));
        return toAmendmentView(amendment, false);
    }

    /**
     * 对修订投票。只有原策略指定的管理角色可以投票，达到修订门槛后修订生效。
     * 与普通签署共用文件版本行锁，保证最后一票签署与最后一票修订不会并发地按两套策略生效。
     */
    @Transactional
    public AmendmentResultView vote(String docCode, int versionNo, int amendmentNo, String eventId,
                                    String signerExternalId, DecisionType decision) {
        Optional<AmendmentDecision> replay = amendmentDecisionRepo.findByEventId(eventId);
        if (replay.isPresent()) {
            return replayOrConflict(replay.get(), docCode, versionNo, amendmentNo, signerExternalId, decision);
        }

        DocumentVersion version = lockVersion(docCode, versionNo);

        Optional<AmendmentDecision> replayUnderLock = amendmentDecisionRepo.findByEventId(eventId);
        if (replayUnderLock.isPresent()) {
            return replayOrConflict(replayUnderLock.get(), docCode, versionNo, amendmentNo, signerExternalId, decision);
        }

        PolicyAmendment amendment = amendmentRepo.findByVersionIdAndAmendmentNo(version.getId(), amendmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.AMENDMENT_NOT_FOUND,
                        "修订不存在: " + docCode + " v" + versionNo + " 修订 " + amendmentNo));

        if (version.getStatus() != VersionStatus.PENDING) {
            throw new BusinessException(ErrorCode.TERMINAL_STATE,
                    "版本已处于终态 " + version.getStatus() + "，修订投票不再接受");
        }
        if (amendment.getStatus() != AmendmentStatus.PENDING) {
            throw new BusinessException(ErrorCode.TERMINAL_STATE, "修订已生效，不再接受投票");
        }

        Signer signer = signerRepo.findByExternalId(signerExternalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNER_NOT_FOUND,
                        "签署人不存在: " + signerExternalId));
        if (!signer.getRoles().contains(version.getAmendRole())) {
            throw new BusinessException(ErrorCode.ELIGIBILITY,
                    "签署人 " + signerExternalId + " 不具备修订管理角色 " + version.getAmendRole());
        }
        if (amendmentDecisionRepo.existsByAmendmentIdAndSignerId(amendment.getId(), signer.getId())) {
            throw new BusinessException(ErrorCode.DUPLICATE_DECISION,
                    "签署人 " + signerExternalId + " 已对该修订投过票");
        }

        amendmentDecisionRepo.saveAndFlush(new AmendmentDecision(amendment, signer, decision, eventId));

        boolean effectiveNow = false;
        Integer carried = null;
        Integer voided = null;
        if (decision == DecisionType.APPROVE
                && amendmentDecisionRepo.countByAmendmentIdAndDecision(amendment.getId(), DecisionType.APPROVE)
                        >= version.getAmendRequiredApprovals()) {
            int[] counts = enact(version, amendment);
            effectiveNow = true;
            carried = counts[0];
            voided = counts[1];
        }

        return new AmendmentResultView(eventId, amendmentNo, amendment.getStatus().name(), false,
                effectiveNow, effectiveNow ? amendment.getPolicyVersion().getPolicyNo() : null, carried, voided);
    }

    /**
     * 修订生效：切换 ACTIVE 策略版本，逐条评估已有决定的沿用，
     * 再按新策略重新评估法定人数。调用方已持有版本行锁。
     * 返回 {沿用数, 失效数}。
     */
    private int[] enact(DocumentVersion version, PolicyAmendment amendment) {
        amendment.markEffective();
        PolicyVersion currentActive = activationService.activePolicy(version.getId());
        currentActive.setStatus(PolicyVersionStatus.SUPERSEDED);
        PolicyVersion newPolicy = amendment.getPolicyVersion();
        newPolicy.setStatus(PolicyVersionStatus.ACTIVE);

        Set<String> newRoles = policyRepo.findByPolicyVersionId(newPolicy.getId()).stream()
                .map(PolicyRequirement::getRole)
                .collect(Collectors.toSet());

        int carried = 0;
        int voided = 0;
        for (SignDecision d : decisionRepo.findByVersionIdOrderByIdAsc(version.getId())) {
            CarryOverOutcome outcome;
            String reason = null;
            if (d.getDecision() != DecisionType.APPROVE) {
                outcome = CarryOverOutcome.VOIDED;
                reason = "REJECT_NOT_CARRIED";
            } else if (d.getVoidedPolicyNo() != null) {
                outcome = CarryOverOutcome.VOIDED;
                reason = "ALREADY_VOIDED";
            } else if (!newRoles.contains(d.getRole())) {
                outcome = CarryOverOutcome.VOIDED;
                reason = "ROLE_NOT_IN_NEW_POLICY";
            } else if (!d.getSigner().getRoles().contains(d.getRole())) {
                outcome = CarryOverOutcome.VOIDED;
                reason = "SIGNER_LOST_ROLE";
            } else {
                outcome = CarryOverOutcome.CARRIED_OVER;
            }
            if (outcome == CarryOverOutcome.VOIDED) {
                voided++;
                if (d.getVoidedPolicyNo() == null) {
                    d.setVoidedPolicyNo(newPolicy.getPolicyNo());
                }
            } else {
                carried++;
            }
            carryOverRepo.save(new CarryOverRecord(amendment, d.getEventId(), d.getSigner().getExternalId(),
                    d.getRole(), d.getDecision(), outcome, reason));
        }

        if (activationService.quorumMet(version.getId())) {
            activationService.activate(version);
        }
        return new int[]{carried, voided};
    }

    @Transactional(readOnly = true)
    public List<AmendmentView> listAmendments(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        return amendmentRepo.findByVersionIdOrderByAmendmentNoAsc(version.getId()).stream()
                .map(a -> toAmendmentView(a, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public AmendmentView getAmendment(String docCode, int versionNo, int amendmentNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        PolicyAmendment amendment = amendmentRepo.findByVersionIdAndAmendmentNo(version.getId(), amendmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.AMENDMENT_NOT_FOUND,
                        "修订不存在: " + docCode + " v" + versionNo + " 修订 " + amendmentNo));
        return toAmendmentView(amendment, false);
    }

    /** 决定沿用明细（仅修订已生效后有数据） */
    @Transactional(readOnly = true)
    public List<CarryOverView> listCarryOver(String docCode, int versionNo, int amendmentNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        PolicyAmendment amendment = amendmentRepo.findByVersionIdAndAmendmentNo(version.getId(), amendmentNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.AMENDMENT_NOT_FOUND,
                        "修订不存在: " + docCode + " v" + versionNo + " 修订 " + amendmentNo));
        return carryOverRepo.findByAmendmentIdOrderByIdAsc(amendment.getId()).stream()
                .map(r -> new CarryOverView(r.getEventId(), r.getSignerExternalId(), r.getRole(),
                        r.getDecision().name(), r.getOutcome().name(), r.getReason()))
                .toList();
    }

    private AmendmentResultView replayOrConflict(AmendmentDecision existing, String docCode, int versionNo,
                                                 int amendmentNo, String signerExternalId, DecisionType decision) {
        PolicyAmendment amendment = existing.getAmendment();
        DocumentVersion version = amendment.getVersion();
        boolean sameContent = version.getDocument().getDocCode().equals(docCode)
                && version.getVersionNo() == versionNo
                && amendment.getAmendmentNo() == amendmentNo
                && existing.getSigner().getExternalId().equals(signerExternalId)
                && existing.getDecision() == decision;
        if (!sameContent) {
            throw new BusinessException(ErrorCode.EVENT_CONFLICT,
                    "事件号 " + existing.getEventId() + " 已存在且内容不同");
        }
        return new AmendmentResultView(existing.getEventId(), amendmentNo, amendment.getStatus().name(),
                true, false, null, null, null);
    }

    private AmendmentView toAmendmentView(PolicyAmendment amendment, boolean replayed) {
        List<PolicyRequirementView> policy = policyRepo.findByPolicyVersionId(amendment.getPolicyVersion().getId())
                .stream()
                .map(r -> new PolicyRequirementView(r.getRole(), r.getRequiredApprovals(), r.isVetoPower()))
                .toList();
        long approvals = amendmentDecisionRepo.countByAmendmentIdAndDecision(amendment.getId(), DecisionType.APPROVE);
        int required = amendment.getVersion().getAmendRequiredApprovals();
        return new AmendmentView(amendment.getAmendmentNo(), amendment.getBasePolicyNo(),
                amendment.getPolicyVersion().getPolicyNo(), amendment.getStatus().name(),
                approvals, required, amendment.getCreatedAt(), amendment.getEffectiveAt(), policy, replayed);
    }

    private void validatePolicy(List<PolicyRequirementView> policy) {
        if (policy == null || policy.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "修订策略不能为空");
        }
        if (policy.stream().map(PolicyRequirementView::role).distinct().count() != policy.size()) {
            throw new BusinessException(ErrorCode.VALIDATION, "修订策略中角色重复");
        }
        if (policy.stream().anyMatch(p -> p.requiredApprovals() < 1)) {
            throw new BusinessException(ErrorCode.VALIDATION, "每个角色至少要求 1 名签署人");
        }
    }

    private boolean samePolicy(List<PolicyRequirementView> requested, List<PolicyRequirement> existing) {
        return normalized(requested.stream()
                        .map(p -> p.role() + "=" + p.requiredApprovals() + ":" + p.vetoPower()))
                .equals(normalized(existing.stream()
                        .map(r -> r.getRole() + "=" + r.getRequiredApprovals() + ":" + r.isVetoPower())));
    }

    private List<String> normalized(java.util.stream.Stream<String> stream) {
        return stream.sorted(Comparator.naturalOrder()).toList();
    }

    private DocumentVersion lockVersion(String docCode, int versionNo) {
        return versionRepo.findByDocCodeAndVersionNoForUpdate(docCode, versionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: " + docCode + " v" + versionNo));
    }

    private DocumentVersion findVersion(String docCode, int versionNo) {
        return versionRepo.findByDocumentDocCodeAndVersionNo(docCode, versionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: " + docCode + " v" + versionNo));
    }
}
