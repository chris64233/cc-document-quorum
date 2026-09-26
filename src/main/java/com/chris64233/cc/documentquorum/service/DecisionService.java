package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.ControlledDocument;
import com.chris64233.cc.documentquorum.domain.Decision;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.PolicyRoleRequirement;
import com.chris64233.cc.documentquorum.domain.SignEvent;
import com.chris64233.cc.documentquorum.domain.Signer;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import com.chris64233.cc.documentquorum.repo.ControlledDocumentRepository;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import com.chris64233.cc.documentquorum.repo.SignEventRepository;
import com.chris64233.cc.documentquorum.repo.SignerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

@Service
public class DecisionService {

    private final DocumentVersionRepository versionRepository;
    private final ControlledDocumentRepository documentRepository;
    private final SignerRepository signerRepository;
    private final SignEventRepository signEventRepository;
    private final TransactionTemplate transactionTemplate;

    public DecisionService(DocumentVersionRepository versionRepository,
                           ControlledDocumentRepository documentRepository,
                           SignerRepository signerRepository,
                           SignEventRepository signEventRepository,
                           TransactionTemplate transactionTemplate) {
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.signerRepository = signerRepository;
        this.signEventRepository = signEventRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public DecisionOutcome decide(Long versionId, String eventNo, String signerExternalId,
                                  String role, Decision decision) {
        try {
            return transactionTemplate.execute(
                    status -> doDecide(versionId, eventNo, signerExternalId, role, decision));
        } catch (DataIntegrityViolationException ex) {
            // 并发下唯一约束冲突：原事务已回滚，在新事务中判定语义。
            return transactionTemplate.execute(status -> recoverConflict(
                    versionId, eventNo, signerExternalId, role, decision, ex));
        }
    }

    private DecisionOutcome recoverConflict(Long versionId, String eventNo, String signerExternalId,
                                            String role, Decision decision,
                                            DataIntegrityViolationException original) {
        String payloadHash = payloadHash(versionId, eventNo, signerExternalId, role, decision);
        Optional<SignEvent> existing = signEventRepository.findByEventNo(eventNo);
        if (existing.isPresent()) {
            SignEvent replayed = existing.get();
            if (!replayed.getPayloadHash().equals(payloadHash)) {
                throw new ApiException(ErrorCode.EVENT_CONFLICT,
                        "事件号已存在且内容不一致: " + eventNo);
            }
            return toOutcome(replayed, replayed.getVersion().getStatus(), true);
        }
        Signer signer = signerRepository.findByExternalId(signerExternalId).orElse(null);
        if (signer != null
                && signEventRepository.existsByVersionIdAndSignerId(versionId, signer.getId())) {
            throw new ApiException(ErrorCode.DUPLICATE_DECISION,
                    "签署人 " + signerExternalId + " 已对该版本作出决定");
        }
        throw original;
    }

    private DecisionOutcome doDecide(Long versionId, String eventNo, String signerExternalId,
                                     String role, Decision decision) {
        String payloadHash = payloadHash(versionId, eventNo, signerExternalId, role, decision);

        Optional<SignEvent> replayed = findReplay(eventNo, payloadHash);
        if (replayed.isPresent()) {
            return toOutcome(replayed.get(), replayed.get().getVersion().getStatus(), true);
        }

        DocumentVersion version = versionRepository.findByIdForUpdate(versionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "版本不存在: " + versionId));

        // 锁等待期间可能有相同事件已提交，获得版本锁后必须复查。
        replayed = findReplay(eventNo, payloadHash);
        if (replayed.isPresent()) {
            return toOutcome(replayed.get(), version.getStatus(), true);
        }

        if (version.getStatus() != VersionStatus.PENDING) {
            throw new ApiException(ErrorCode.TERMINAL_STATE,
                    "版本已处于终态 " + version.getStatus() + "，不再接受新决定");
        }

        Signer signer = signerRepository.findByExternalId(signerExternalId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "签署人不存在: " + signerExternalId));

        PolicyRoleRequirement requirement = null;
        for (PolicyRoleRequirement candidate : version.getPolicyRequirements()) {
            if (candidate.getRole().equals(role)) {
                requirement = candidate;
                break;
            }
        }
        if (requirement == null) {
            throw new ApiException(ErrorCode.ELIGIBILITY, "角色不在该版本签署策略中: " + role);
        }
        if (!signer.getRoles().contains(role)) {
            throw new ApiException(ErrorCode.ELIGIBILITY,
                    "签署人 " + signerExternalId + " 不具备角色: " + role);
        }
        if (signEventRepository.existsByVersionIdAndSignerId(versionId, signer.getId())) {
            throw new ApiException(ErrorCode.DUPLICATE_DECISION,
                    "签署人 " + signerExternalId + " 已对该版本作出决定");
        }

        // 与版本状态切换保持一致的加锁顺序：先版本行，再文件行。
        ControlledDocument document = documentRepository
                .findByIdForUpdate(version.getDocument().getId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "文件不存在"));

        SignEvent event = new SignEvent(eventNo, version, signer, role, decision, payloadHash);
        signEventRepository.saveAndFlush(event);

        if (decision == Decision.REJECT && requirement.isVeto()) {
            version.setStatus(VersionStatus.REJECTED);
        } else if (decision == Decision.APPROVE && allThresholdsMet(version)) {
            versionRepository.findByDocumentIdAndStatus(document.getId(), VersionStatus.EFFECTIVE)
                    .ifPresent(current -> current.setStatus(VersionStatus.SUPERSEDED));
            version.setStatus(VersionStatus.EFFECTIVE);
        }
        versionRepository.save(version);
        return toOutcome(event, version.getStatus(), false);
    }

    private Optional<SignEvent> findReplay(String eventNo, String payloadHash) {
        Optional<SignEvent> existing = signEventRepository.findByEventNo(eventNo);
        if (existing.isPresent() && !existing.get().getPayloadHash().equals(payloadHash)) {
            throw new ApiException(ErrorCode.EVENT_CONFLICT,
                    "事件号已存在且内容不一致: " + eventNo);
        }
        return existing;
    }

    private DecisionOutcome toOutcome(SignEvent event, VersionStatus status, boolean replayed) {
        return new DecisionOutcome(event.getEventNo(), event.getSigner().getExternalId(),
                event.getRole(), event.getDecision(), status, replayed);
    }

    private boolean allThresholdsMet(DocumentVersion version) {
        Map<String, Long> counts = new java.util.HashMap<>();
        for (Object[] row : signEventRepository.countByVersionAndDecisionGroupByRole(
                version.getId(), Decision.APPROVE)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        for (PolicyRoleRequirement requirement : version.getPolicyRequirements()) {
            long count = counts.getOrDefault(requirement.getRole(), 0L);
            if (count < requirement.getRequiredApprovals()) {
                return false;
            }
        }
        return true;
    }

    private static String payloadHash(Long versionId, String eventNo, String signerExternalId,
                                      String role, Decision decision) {
        String payload = versionId + "|" + eventNo + "|" + signerExternalId + "|" + role + "|" + decision;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record DecisionOutcome(String eventNo, String signerExternalId, String role,
                                  Decision decision, VersionStatus versionStatus, boolean replayed) {
    }
}
