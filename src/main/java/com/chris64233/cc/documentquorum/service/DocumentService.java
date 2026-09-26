package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.ControlledDocument;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.PolicyRoleRequirement;
import com.chris64233.cc.documentquorum.repo.ControlledDocumentRepository;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DocumentService {

    private final ControlledDocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;

    public DocumentService(ControlledDocumentRepository documentRepository,
                           DocumentVersionRepository versionRepository) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
    }

    @Transactional
    public ControlledDocument createDocument(String code, String name) {
        if (documentRepository.existsByCode(code)) {
            throw new ApiException(ErrorCode.VALIDATION, "文件编码已存在: " + code);
        }
        return documentRepository.save(new ControlledDocument(code, name));
    }

    @Transactional
    public DocumentVersion createVersion(Long documentId, String content,
                                         List<PolicyRequirementCommand> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "签署策略至少需要一个角色要求");
        }
        ControlledDocument document = documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "文件不存在: " + documentId));
        int nextNumber = versionRepository.findMaxVersionNumber(documentId) + 1;
        DocumentVersion version = new DocumentVersion(document, nextNumber, content);
        for (PolicyRequirementCommand requirement : requirements) {
            version.addPolicyRequirement(new PolicyRoleRequirement(
                    version, requirement.role(), requirement.requiredApprovals(), requirement.veto()));
        }
        return versionRepository.save(version);
    }

    @Transactional(readOnly = true)
    public List<DocumentVersion> listVersions(Long documentId) {
        if (!documentRepository.existsById(documentId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "文件不存在: " + documentId);
        }
        List<DocumentVersion> versions = versionRepository.findByDocumentIdOrderByVersionNumberAsc(documentId);
        versions.forEach(version -> version.getPolicyRequirements().size());
        return versions;
    }

    public record PolicyRequirementCommand(String role, int requiredApprovals, boolean veto) {
    }
}
