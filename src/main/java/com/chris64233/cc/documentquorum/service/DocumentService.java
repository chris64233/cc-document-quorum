package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.ControlledDocument;
import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.PolicyRequirement;
import com.chris64233.cc.documentquorum.domain.PolicyVersionMeta;
import com.chris64233.cc.documentquorum.domain.SignDecision;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.ControlledDocumentRepository;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import com.chris64233.cc.documentquorum.repo.PolicyRequirementRepository;
import com.chris64233.cc.documentquorum.repo.PolicyVersionMetaRepository;
import com.chris64233.cc.documentquorum.repo.SignDecisionRepository;
import com.chris64233.cc.documentquorum.service.views.CarryOverDecisionView;
import com.chris64233.cc.documentquorum.service.views.CarryOverView;
import com.chris64233.cc.documentquorum.service.views.DecisionView;
import com.chris64233.cc.documentquorum.service.views.DocumentView;
import com.chris64233.cc.documentquorum.service.views.EffectivenessView;
import com.chris64233.cc.documentquorum.service.views.PolicyDiffView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import com.chris64233.cc.documentquorum.service.views.PolicyVersionView;
import com.chris64233.cc.documentquorum.service.views.ProgressView;
import com.chris64233.cc.documentquorum.service.views.RolePolicyDiffView;
import com.chris64233.cc.documentquorum.service.views.RoleProgressView;
import com.chris64233.cc.documentquorum.service.views.VersionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private final ControlledDocumentRepository documentRepo;
    private final DocumentVersionRepository versionRepo;
    private final PolicyRequirementRepository policyRepo;
    private final PolicyVersionMetaRepository policyMetaRepo;
    private final SignDecisionRepository decisionRepo;

    public DocumentService(ControlledDocumentRepository documentRepo,
                           DocumentVersionRepository versionRepo,
                           PolicyRequirementRepository policyRepo,
                           PolicyVersionMetaRepository policyMetaRepo,
                           SignDecisionRepository decisionRepo) {
        this.documentRepo = documentRepo;
        this.versionRepo = versionRepo;
        this.policyRepo = policyRepo;
        this.policyMetaRepo = policyMetaRepo;
        this.decisionRepo = decisionRepo;
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

    @Transactional
    public VersionView createVersion(String docCode, String content, List<PolicyRequirementView> policy,
                                     String amendmentRole, Integer amendmentThreshold) {
        validatePolicy(policy);
        validateAmendmentConfig(amendmentRole, amendmentThreshold);
        ControlledDocument doc = documentRepo.findByDocCodeForUpdate(docCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文件不存在: " + docCode));
        int nextVersionNo = versionRepo.findMaxVersionNoByDocumentId(doc.getId()).orElse(0) + 1;
        DocumentVersion version = versionRepo.save(new DocumentVersion(doc, nextVersionNo, content));
        policyMetaRepo.save(new PolicyVersionMeta(version, 1, amendmentRole, amendmentThreshold, null));
        List<PolicyRequirement> requirements = policy.stream()
                .map(p -> policyRepo.save(
                        new PolicyRequirement(version, 1, p.role(), p.requiredApprovals(), p.vetoPower())))
                .toList();
        return toVersionView(version, requirements);
    }

    static void validatePolicy(List<PolicyRequirementView> policy) {
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

    static void validateAmendmentConfig(String amendmentRole, Integer amendmentThreshold) {
        if (amendmentRole == null && amendmentThreshold == null) {
            return;
        }
        if (amendmentRole == null || amendmentRole.isBlank() || amendmentThreshold == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "管理角色与修订门槛必须同时提供");
        }
        if (amendmentThreshold < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "修订门槛至少为 1");
        }
    }

    @Transactional(readOnly = true)
    public List<VersionView> listVersions(String docCode) {
        findDocument(docCode);
        return versionRepo.findByDocumentDocCodeOrderByVersionNoAsc(docCode).stream()
                .map(v -> toVersionView(v, currentRequirements(v)))
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionView getVersion(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        return toVersionView(version, currentRequirements(version));
    }

    @Transactional(readOnly = true)
    public ProgressView getProgress(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        int policyVersionNo = version.getCurrentPolicyVersionNo();
        List<PolicyRequirement> requirements =
                policyRepo.findByVersionIdAndPolicyVersionNo(version.getId(), policyVersionNo);
        Map<String, Long> approvals = countedApprovalsByRole(version.getId());
        List<RoleProgressView> roles = requirements.stream()
                .map(r -> {
                    long approved = approvals.getOrDefault(r.getRole(), 0L);
                    return new RoleProgressView(r.getRole(), r.getRequiredApprovals(), approved,
                            r.isVetoPower(), approved >= r.getRequiredApprovals());
                })
                .toList();
        return new ProgressView(version.getVersionNo(), version.getStatus().name(), policyVersionNo, roles);
    }

    @Transactional(readOnly = true)
    public List<DecisionView> listDecisions(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        return decisionRepo.findByVersionIdOrderByIdAsc(version.getId()).stream()
                .map(this::toDecisionView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PolicyVersionView> listPolicyVersions(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        int current = version.getCurrentPolicyVersionNo();
        return policyMetaRepo.findByVersionIdOrderByPolicyVersionNoAsc(version.getId()).stream()
                .map(meta -> new PolicyVersionView(meta.getPolicyVersionNo(),
                        meta.getPolicyVersionNo() == current,
                        meta.getAmendmentRole(), meta.getAmendmentThreshold(),
                        meta.getSourceAmendmentNo(),
                        toRequirementViews(policyRepo.findByVersionIdAndPolicyVersionNo(
                                version.getId(), meta.getPolicyVersionNo()))))
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyDiffView getPolicyDiff(String docCode, int versionNo, int fromPolicyVersionNo,
                                        int toPolicyVersionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        Map<String, PolicyRequirement> from = requirementsByRole(version.getId(), fromPolicyVersionNo);
        Map<String, PolicyRequirement> to = requirementsByRole(version.getId(), toPolicyVersionNo);
        Set<String> roles = new TreeSet<>(from.keySet());
        roles.addAll(to.keySet());
        List<RolePolicyDiffView> changes = new ArrayList<>();
        for (String role : roles) {
            PolicyRequirement oldReq = from.get(role);
            PolicyRequirement newReq = to.get(role);
            String changeType;
            if (oldReq == null) {
                changeType = "ADDED";
            } else if (newReq == null) {
                changeType = "REMOVED";
            } else if (oldReq.getRequiredApprovals() != newReq.getRequiredApprovals()
                    || oldReq.isVetoPower() != newReq.isVetoPower()) {
                changeType = "MODIFIED";
            } else {
                changeType = "UNCHANGED";
            }
            changes.add(new RolePolicyDiffView(role, changeType,
                    oldReq == null ? null : oldReq.getRequiredApprovals(),
                    newReq == null ? null : newReq.getRequiredApprovals(),
                    oldReq == null ? null : oldReq.isVetoPower(),
                    newReq == null ? null : newReq.isVetoPower()));
        }
        return new PolicyDiffView(version.getVersionNo(), fromPolicyVersionNo, toPolicyVersionNo, changes);
    }

    @Transactional(readOnly = true)
    public CarryOverView getCarryOver(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        int current = version.getCurrentPolicyVersionNo();
        Set<String> currentRoles = policyRepo.findByVersionIdAndPolicyVersionNo(version.getId(), current)
                .stream().map(PolicyRequirement::getRole).collect(Collectors.toSet());
        List<CarryOverDecisionView> decisions = decisionRepo.findByVersionIdOrderByIdAsc(version.getId())
                .stream()
                .map(d -> new CarryOverDecisionView(d.getEventId(), d.getSigner().getExternalId(),
                        d.getRole(), d.getDecision().name(), d.getPolicyVersionNo(), d.isCounted(),
                        carryOverReason(d, currentRoles)))
                .toList();
        return new CarryOverView(version.getVersionNo(), current, decisions);
    }

    private String carryOverReason(SignDecision decision, Set<String> currentRoles) {
        if (decision.getDecision() != DecisionType.APPROVE) {
            return "REJECT_NOT_COUNTED";
        }
        if (decision.isCounted()) {
            return "CARRIED_OVER";
        }
        if (!currentRoles.contains(decision.getRole())) {
            return "ROLE_NOT_IN_CURRENT_POLICY";
        }
        if (!decision.getSigner().getRoles().contains(decision.getRole())) {
            return "SIGNER_ROLE_LOST";
        }
        return "NOT_COUNTED";
    }

    @Transactional(readOnly = true)
    public EffectivenessView getEffectiveness(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        if (version.getEffectivePolicyVersionNo() == null) {
            throw new BusinessException(ErrorCode.VERSION_NOT_EFFECTIVE,
                    "版本尚未生效，无生效依据: " + docCode + " v" + versionNo);
        }
        int effectivePolicy = version.getEffectivePolicyVersionNo();
        List<PolicyRequirementView> requirements =
                toRequirementViews(policyRepo.findByVersionIdAndPolicyVersionNo(version.getId(), effectivePolicy));
        List<DecisionView> countedDecisions = decisionRepo.findByVersionIdOrderByIdAsc(version.getId())
                .stream()
                .filter(d -> d.isCounted() && d.getDecision() == DecisionType.APPROVE)
                .map(this::toDecisionView)
                .toList();
        return new EffectivenessView(version.getVersionNo(), version.getStatus().name(), effectivePolicy,
                version.getActivatedAt(), requirements, countedDecisions);
    }

    Map<String, Long> countedApprovalsByRole(Long versionId) {
        return decisionRepo.findByVersionIdOrderByIdAsc(versionId).stream()
                .filter(d -> d.isCounted() && d.getDecision() == DecisionType.APPROVE)
                .collect(Collectors.groupingBy(SignDecision::getRole, Collectors.counting()));
    }

    private Map<String, PolicyRequirement> requirementsByRole(Long versionId, int policyVersionNo) {
        List<PolicyRequirement> requirements =
                policyRepo.findByVersionIdAndPolicyVersionNo(versionId, policyVersionNo);
        if (requirements.isEmpty()) {
            throw new BusinessException(ErrorCode.POLICY_VERSION_NOT_FOUND,
                    "策略版本不存在: 策略 v" + policyVersionNo);
        }
        return requirements.stream()
                .collect(Collectors.toMap(PolicyRequirement::getRole, Function.identity()));
    }

    private List<PolicyRequirement> currentRequirements(DocumentVersion version) {
        return policyRepo.findByVersionIdAndPolicyVersionNo(version.getId(),
                version.getCurrentPolicyVersionNo());
    }

    private List<PolicyRequirementView> toRequirementViews(List<PolicyRequirement> requirements) {
        return requirements.stream()
                .map(r -> new PolicyRequirementView(r.getRole(), r.getRequiredApprovals(), r.isVetoPower()))
                .toList();
    }

    private DecisionView toDecisionView(SignDecision d) {
        return new DecisionView(d.getEventId(), d.getSigner().getExternalId(), d.getRole(),
                d.getDecision().name(), d.getPolicyVersionNo(), d.isCounted(), d.getCreatedAt());
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

    private VersionView toVersionView(DocumentVersion version, List<PolicyRequirement> requirements) {
        return new VersionView(version.getId(), version.getVersionNo(), version.getStatus().name(),
                version.getContent(), version.getCurrentPolicyVersionNo(), version.getCreatedAt(),
                toRequirementViews(requirements));
    }
}
