package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.ControlledDocument;
import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.PolicyRequirement;
import com.chris64233.cc.documentquorum.domain.SignDecision;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.ControlledDocumentRepository;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import com.chris64233.cc.documentquorum.repo.PolicyRequirementRepository;
import com.chris64233.cc.documentquorum.repo.SignDecisionRepository;
import com.chris64233.cc.documentquorum.service.views.DecisionView;
import com.chris64233.cc.documentquorum.service.views.DocumentView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import com.chris64233.cc.documentquorum.service.views.ProgressView;
import com.chris64233.cc.documentquorum.service.views.RoleProgressView;
import com.chris64233.cc.documentquorum.service.views.VersionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DocumentService {

    private final ControlledDocumentRepository documentRepo;
    private final DocumentVersionRepository versionRepo;
    private final PolicyRequirementRepository policyRepo;
    private final SignDecisionRepository decisionRepo;

    public DocumentService(ControlledDocumentRepository documentRepo,
                           DocumentVersionRepository versionRepo,
                           PolicyRequirementRepository policyRepo,
                           SignDecisionRepository decisionRepo) {
        this.documentRepo = documentRepo;
        this.versionRepo = versionRepo;
        this.policyRepo = policyRepo;
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
        if (policy == null || policy.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "签署策略不能为空");
        }
        if (policy.stream().map(PolicyRequirementView::role).distinct().count() != policy.size()) {
            throw new BusinessException(ErrorCode.VALIDATION, "签署策略中角色重复");
        }
        if (policy.stream().anyMatch(p -> p.requiredApprovals() < 1)) {
            throw new BusinessException(ErrorCode.VALIDATION, "每个角色至少要求 1 名签署人");
        }
        ControlledDocument doc = documentRepo.findByDocCodeForUpdate(docCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND, "文件不存在: " + docCode));
        int nextVersionNo = versionRepo.findMaxVersionNoByDocumentId(doc.getId()).orElse(0) + 1;
        DocumentVersion version = versionRepo.save(new DocumentVersion(doc, nextVersionNo, content));
        List<PolicyRequirement> requirements = policy.stream()
                .map(p -> policyRepo.save(
                        new PolicyRequirement(version, p.role(), p.requiredApprovals(), p.vetoPower())))
                .toList();
        return toVersionView(version, requirements);
    }

    @Transactional(readOnly = true)
    public List<VersionView> listVersions(String docCode) {
        findDocument(docCode);
        return versionRepo.findByDocumentDocCodeOrderByVersionNoAsc(docCode).stream()
                .map(v -> toVersionView(v, policyRepo.findByVersionId(v.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public VersionView getVersion(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        return toVersionView(version, policyRepo.findByVersionId(version.getId()));
    }

    @Transactional(readOnly = true)
    public ProgressView getProgress(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        List<PolicyRequirement> requirements = policyRepo.findByVersionId(version.getId());
        Map<String, Long> approvals = decisionRepo.findByVersionIdOrderByIdAsc(version.getId()).stream()
                .filter(d -> d.getDecision() == DecisionType.APPROVE)
                .collect(Collectors.groupingBy(SignDecision::getRole, Collectors.counting()));
        List<RoleProgressView> roles = requirements.stream()
                .map(r -> {
                    long approved = approvals.getOrDefault(r.getRole(), 0L);
                    return new RoleProgressView(r.getRole(), r.getRequiredApprovals(), approved,
                            r.isVetoPower(), approved >= r.getRequiredApprovals());
                })
                .toList();
        return new ProgressView(version.getVersionNo(), version.getStatus().name(), roles);
    }

    @Transactional(readOnly = true)
    public List<DecisionView> listDecisions(String docCode, int versionNo) {
        DocumentVersion version = findVersion(docCode, versionNo);
        return decisionRepo.findByVersionIdOrderByIdAsc(version.getId()).stream()
                .map(d -> new DecisionView(d.getEventId(), d.getSigner().getExternalId(), d.getRole(),
                        d.getDecision().name(), d.getCreatedAt()))
                .toList();
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
        List<PolicyRequirementView> policy = requirements.stream()
                .map(r -> new PolicyRequirementView(r.getRole(), r.getRequiredApprovals(), r.isVetoPower()))
                .toList();
        return new VersionView(version.getId(), version.getVersionNo(), version.getStatus().name(),
                version.getContent(), version.getCreatedAt(), policy);
    }
}
