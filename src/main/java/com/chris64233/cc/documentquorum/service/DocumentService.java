package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.ControlledDocument;
import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.PolicyAmendment;
import com.chris64233.cc.documentquorum.domain.PolicyRequirement;
import com.chris64233.cc.documentquorum.domain.PolicyVersion;
import com.chris64233.cc.documentquorum.domain.PolicyVersionStatus;
import com.chris64233.cc.documentquorum.domain.SignDecision;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.ControlledDocumentRepository;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import com.chris64233.cc.documentquorum.repo.PolicyAmendmentRepository;
import com.chris64233.cc.documentquorum.repo.PolicyRequirementRepository;
import com.chris64233.cc.documentquorum.repo.PolicyVersionRepository;
import com.chris64233.cc.documentquorum.repo.SignDecisionRepository;
import com.chris64233.cc.documentquorum.service.views.DecisionView;
import com.chris64233.cc.documentquorum.service.views.DocumentView;
import com.chris64233.cc.documentquorum.service.views.EffectiveBasisView;
import com.chris64233.cc.documentquorum.service.views.PolicyDiffView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import com.chris64233.cc.documentquorum.service.views.PolicyVersionView;
import com.chris64233.cc.documentquorum.service.views.ProgressView;
import com.chris64233.cc.documentquorum.service.views.RolePolicyChangeView;
import com.chris64233.cc.documentquorum.service.views.RoleProgressView;
import com.chris64233.cc.documentquorum.service.views.VersionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private final ControlledDocumentRepository documentRepo;
    private final DocumentVersionRepository versionRepo;
    private final PolicyVersionRepository policyVersionRepo;
    private final PolicyRequirementRepository policyRepo;
    private final SignDecisionRepository decisionRepo;
    private final PolicyAmendmentRepository amendmentRepo;
    private final ActivationService activationService;

    public DocumentService(ControlledDocumentRepository documentRepo,
                           DocumentVersionRepository versionRepo,
                           PolicyVersionRepository policyVersionRepo,
                           PolicyRequirementRepository policyRepo,
                           SignDecisionRepository decisionRepo,
                           PolicyAmendmentRepository amendmentRepo,
                           ActivationService activationService) {
        this.documentRepo = documentRepo;
        this.versionRepo = versionRepo;
        this.policyVersionRepo = policyVersionRepo;
        this.policyRepo = policyRepo;
        this.decisionRepo = decisionRepo;
        this.amendmentRepo = amendmentRepo;
        this.activationService = activationService;
    }

    @Transactional
    public DocumentView createDocument(String docCode, String title) {
        documentRepo.findByDocCode(docCode).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.DUPLICATE_DOCUMENT, "文件编号已存在: " + docCode);
        });
        ControlledDocument saved = documentRepo.save(new ControlledDocument(docCode, title));
        return new DocumentView(saved.getId(), saved.getDocCode(), saved.getTitle());
    }

    @Transactional(readOnly = true)
    public DocumentView getDocument(String docCode) {
        ControlledDocument doc = findDocument(docCode);
        return new DocumentView(doc.getId(), doc.getDocCode(), doc.getTitle());
    }

    @Transactional
    public VersionView createVersion(String docCode, String content, List<PolicyRequirementView> policy) {
        return createVersion(docCode, content, policy, null, null);
    }

    /**
     * 创建文件版本。初始策略即 1 号策略版本（直接 ACTIVE）。
     * amendRole/amendRequiredApprovals 为原策略指定的修订管理角色与修订门槛，
     * 随版本固化，之后的修订不能改变它们。
     */
    @Transactional
    public VersionView createVersion(String docCode, String content, List<PolicyRequirementView> policy,
                                     String amendRole, Integer amendRequiredApprovals) {
        validatePolicy(policy);
        if (amendRole != null && (amendRequiredApprovals == null || amendRequiredApprovals < 1)) {
            throw new BusinessException(ErrorCode.VALIDATION, "修订门槛至少为 1");
        }
        ControlledDocument doc = documentRepo.findByDocCodeForUpdate(docCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文件不存在: " + docCode));
        int nextVersionNo = versionRepo.findMaxVersionNoByDocumentId(doc.getId()).orElse(0) + 1;
        DocumentVersion version = versionRepo.save(
                new DocumentVersion(doc, nextVersionNo, content, amendRole, amendRequiredApprovals));
        PolicyVersion initialPolicy = policyVersionRepo.save(
                new PolicyVersion(version, 1, PolicyVersionStatus.ACTIVE));
        List<PolicyRequirement> requirements = policy.stream()
                .map(p -> policyRepo.save(
                        new PolicyRequirement(initialPolicy, p.role(), p.requiredApprovals(), p.vetoPower())))
                .toList();
        return toVersionView(version, initialPolicy, requirements);
    }

    @Transactional(readOnly = true)
    public List<VersionView> listVersions(String docCode) {
        findDocument(docCode);
        return versionRepo.findByDocumentDocCodeOrderByVersionNoAsc(docCode).stream()
                .map(this::toVersionViewWithActivePolicy)
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionView getVersion(String docCode, int versionNo) {
        return toVersionViewWithActivePolicy(findVersion(docCode, versionNo));
    }

    /** 策略版本历史（含各版本完整要求） */
    @Transactional(readOnly = true)
    public List<PolicyVersionView> listPolicyVersions(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        return policyVersionRepo.findByVersionIdOrderByPolicyNoAsc(version.getId()).stream()
                .map(pv -> new PolicyVersionView(pv.getPolicyNo(), pv.getStatus().name(), pv.getCreatedAt(),
                        toPolicyViews(policyRepo.findByPolicyVersionId(pv.getId()))))
                .toList();
    }

    /** 两个策略版本之间的差异（按角色对比门槛与否决规则） */
    @Transactional(readOnly = true)
    public PolicyDiffView diffPolicies(String docCode, int versionNo, int fromPolicyNo, int toPolicyNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        Map<String, PolicyRequirement> from = requirementsByRole(version.getId(), fromPolicyNo);
        Map<String, PolicyRequirement> to = requirementsByRole(version.getId(), toPolicyNo);
        List<RolePolicyChangeView> changes = new ArrayList<>();
        for (PolicyRequirement oldReq : from.values()) {
            PolicyRequirement newReq = to.get(oldReq.getRole());
            if (newReq == null) {
                changes.add(new RolePolicyChangeView(oldReq.getRole(), "REMOVED",
                        oldReq.getRequiredApprovals(), null, oldReq.isVetoPower(), null));
            } else if (oldReq.getRequiredApprovals() != newReq.getRequiredApprovals()
                    || oldReq.isVetoPower() != newReq.isVetoPower()) {
                changes.add(new RolePolicyChangeView(oldReq.getRole(), "CHANGED",
                        oldReq.getRequiredApprovals(), newReq.getRequiredApprovals(),
                        oldReq.isVetoPower(), newReq.isVetoPower()));
            }
        }
        for (PolicyRequirement newReq : to.values()) {
            if (!from.containsKey(newReq.getRole())) {
                changes.add(new RolePolicyChangeView(newReq.getRole(), "ADDED",
                        null, newReq.getRequiredApprovals(), null, newReq.isVetoPower()));
            }
        }
        return new PolicyDiffView(fromPolicyNo, toPolicyNo, changes);
    }

    /** 按当前 ACTIVE 策略版本计算的达成进度；被修订作废的决定不计入 */
    @Transactional(readOnly = true)
    public ProgressView getProgress(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        PolicyVersion active = activationService.activePolicy(version.getId());
        List<PolicyRequirement> requirements = policyRepo.findByPolicyVersionId(active.getId());
        Map<String, Long> approvals = decisionRepo.findByVersionIdOrderByIdAsc(version.getId()).stream()
                .filter(d -> d.getDecision() == DecisionType.APPROVE && d.getVoidedPolicyNo() == null)
                .collect(Collectors.groupingBy(SignDecision::getRole, Collectors.counting()));
        List<RoleProgressView> roles = requirements.stream()
                .map(r -> {
                    long approved = approvals.getOrDefault(r.getRole(), 0L);
                    return new RoleProgressView(r.getRole(), r.getRequiredApprovals(), approved,
                            r.isVetoPower(), approved >= r.getRequiredApprovals());
                })
                .toList();
        return new ProgressView(version.getVersionNo(), version.getStatus().name(), active.getPolicyNo(), roles);
    }

    /** 生效依据：版本状态、据以生效的策略版本、触发来源（初始策略或某次修订）、生效时间 */
    @Transactional(readOnly = true)
    public EffectiveBasisView getEffectiveBasis(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        PolicyVersion active = activationService.activePolicy(version.getId());
        Integer amendmentNo = amendmentRepo.findByPolicyVersionId(active.getId())
                .map(PolicyAmendment::getAmendmentNo)
                .orElse(null);
        String basis;
        if (version.getStatus() != VersionStatus.EFFECTIVE) {
            basis = "NOT_EFFECTIVE";
        } else {
            basis = amendmentNo == null ? "INITIAL_POLICY_QUORUM" : "AMENDED_POLICY_QUORUM";
        }
        return new EffectiveBasisView(docCode, versionNo, version.getStatus().name(),
                active.getPolicyNo(), basis, amendmentNo, version.getEffectiveAt());
    }

    @Transactional(readOnly = true)
    public List<DecisionView> listDecisions(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        return decisionRepo.findByVersionIdOrderByIdAsc(version.getId()).stream()
                .map(d -> new DecisionView(d.getEventId(), d.getSigner().getExternalId(), d.getRole(),
                        d.getDecision().name(), d.getPolicyNo(), d.getVoidedPolicyNo(), d.getCreatedAt()))
                .toList();
    }

    private Map<String, PolicyRequirement> requirementsByRole(Long versionId, int policyNo) {
        PolicyVersion policyVersion = policyVersionRepo.findByVersionIdAndPolicyNo(versionId, policyNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.POLICY_VERSION_NOT_FOUND,
                        "策略版本不存在: policyNo=" + policyNo));
        return policyRepo.findByPolicyVersionId(policyVersion.getId()).stream()
                .collect(Collectors.toMap(PolicyRequirement::getRole, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    private void validatePolicy(List<PolicyRequirementView> policy) {
        if (policy == null || policy.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "签署策略不能为空");
        }
        if (policy.stream().map(PolicyRequirementView::role).distinct().count() != policy.size()) {
            throw new BusinessException(ErrorCode.VALIDATION, "签署策略中角色重复");
        }
        if (policy.stream().anyMatch(p -> p.requiredApprovals() < 1)) {
            throw new BusinessException(ErrorCode.VALIDATION, "每个角色至少要求 1 名签署人");
        }
    }

    private ControlledDocument findDocument(String docCode) {
        return documentRepo.findByDocCode(docCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文件不存在: " + docCode));
    }

    private DocumentVersion findVersion(String docCode, int versionNo) {
        findDocument(docCode);
        return versionRepo.findByDocumentDocCodeAndVersionNo(docCode, versionNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: " + docCode + " v" + versionNo));
    }

    private VersionView toVersionViewWithActivePolicy(DocumentVersion version) {
        PolicyVersion active = activationService.activePolicy(version.getId());
        return toVersionView(version, active, policyRepo.findByPolicyVersionId(active.getId()));
    }

    private VersionView toVersionView(DocumentVersion version, PolicyVersion activePolicy,
                                      List<PolicyRequirement> requirements) {
        return new VersionView(version.getId(), version.getVersionNo(), version.getStatus().name(),
                version.getContent(), version.getCreatedAt(), toPolicyViews(requirements),
                activePolicy.getPolicyNo(), version.getAmendRole(), version.getAmendRequiredApprovals());
    }

    private List<PolicyRequirementView> toPolicyViews(List<PolicyRequirement> requirements) {
        return requirements.stream()
                .map(r -> new PolicyRequirementView(r.getRole(), r.getRequiredApprovals(), r.isVetoPower()))
                .toList();
    }
}
