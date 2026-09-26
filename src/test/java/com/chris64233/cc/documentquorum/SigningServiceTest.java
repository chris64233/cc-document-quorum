package com.chris64233.cc.documentquorum;

import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.domain.EffectiveVersion;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.EffectiveVersionRepository;
import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.service.SignerService;
import com.chris64233.cc.documentquorum.service.SigningService;
import com.chris64233.cc.documentquorum.service.views.DecisionResultView;
import com.chris64233.cc.documentquorum.service.views.DecisionView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import com.chris64233.cc.documentquorum.service.views.ProgressView;
import com.chris64233.cc.documentquorum.service.views.VersionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SigningServiceTest {

    @Autowired
    DocumentService documentService;
    @Autowired
    SigningService signingService;
    @Autowired
    SignerService signerService;
    @Autowired
    EffectiveVersionRepository effectiveRepo;

    String docCode;

    @BeforeEach
    void setUp() {
        docCode = "DOC-" + UUID.randomUUID();
        documentService.createDocument(docCode, "受控文件");
        signerService.createSigner("qa1-" + suffix(), "质检一", Set.of("QA"));
        signerService.createSigner("rev1-" + suffix(), "评审一", Set.of("REVIEWER"));
        signerService.createSigner("rev2-" + suffix(), "评审二", Set.of("REVIEWER"));
        signerService.createSigner("outsider-" + suffix(), "局外人", Set.of("OTHER"));
    }

    private String suffix() {
        return docCode.substring(4, 12);
    }

    private String qa1() {
        return "qa1-" + suffix();
    }

    private String rev1() {
        return "rev1-" + suffix();
    }

    private String rev2() {
        return "rev2-" + suffix();
    }

    private String outsider() {
        return "outsider-" + suffix();
    }

    private VersionView createDefaultVersion() {
        return documentService.createVersion(docCode, "v-content", List.of(
                new PolicyRequirementView("QA", 1, true),
                new PolicyRequirementView("REVIEWER", 2, false)));
    }

    private String evt(String eventId) {
        return suffix() + "-" + eventId;
    }

    private DecisionResultView approve(int versionNo, String eventId, String signer, String role) {
        return signingService.submit(docCode, versionNo, evt(eventId), signer, role, DecisionType.APPROVE);
    }

    @Test
    void quorumReachedActivatesVersion() {
        createDefaultVersion();
        assertThat(approve(1, "e1", qa1(), "QA").versionStatus()).isEqualTo("PENDING");
        assertThat(approve(1, "e2", rev1(), "REVIEWER").versionStatus()).isEqualTo("PENDING");
        DecisionResultView last = approve(1, "e3", rev2(), "REVIEWER");
        assertThat(last.versionStatus()).isEqualTo("EFFECTIVE");
        assertThat(last.replayed()).isFalse();

        ProgressView progress = documentService.getProgress(docCode, 1);
        assertThat(progress.status()).isEqualTo("EFFECTIVE");
        assertThat(progress.roles()).allSatisfy(role -> {
            assertThat(role.met()).isTrue();
            assertThat(role.approvedCount()).isGreaterThanOrEqualTo(role.requiredApprovals());
        });
    }

    @Test
    void vetoRejectMovesVersionToRejectedTerminal() {
        createDefaultVersion();
        approve(1, "e1", rev1(), "REVIEWER");
        DecisionResultView veto = signingService.submit(docCode, 1, evt("e2"), qa1(), "QA", DecisionType.REJECT);
        assertThat(veto.versionStatus()).isEqualTo("REJECTED");

        assertThatThrownBy(() -> approve(1, "e3", rev2(), "REVIEWER"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.TERMINAL_STATE);
    }

    @Test
    void nonVetoRejectDoesNotTerminateVersion() {
        documentService.createVersion(docCode, "v-content", List.of(
                new PolicyRequirementView("QA", 1, false),
                new PolicyRequirementView("REVIEWER", 1, false)));
        DecisionResultView reject = signingService.submit(docCode, 1, evt("e1"), rev1(), "REVIEWER", DecisionType.REJECT);
        assertThat(reject.versionStatus()).isEqualTo("PENDING");
        approve(1, "e2", qa1(), "QA");
        assertThat(approve(1, "e3", rev2(), "REVIEWER").versionStatus()).isEqualTo("EFFECTIVE");
    }

    @Test
    void signerCanOnlyDecideOncePerVersion() {
        createDefaultVersion();
        approve(1, "e1", qa1(), "QA");
        assertThatThrownBy(() -> approve(1, "e2", qa1(), "QA"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_DECISION);
    }

    @Test
    void sameEventSameContentReplayReturnsOriginalResult() {
        createDefaultVersion();
        approve(1, "evt-1", qa1(), "QA");
        DecisionResultView replay = approve(1, "evt-1", qa1(), "QA");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.versionStatus()).isEqualTo("PENDING");
        assertThat(documentService.listDecisions(docCode, 1)).hasSize(1);
    }

    @Test
    void replayWorksEvenAfterTerminalState() {
        createDefaultVersion();
        approve(1, "e1", qa1(), "QA");
        approve(1, "e2", rev1(), "REVIEWER");
        approve(1, "e3", rev2(), "REVIEWER");
        DecisionResultView replay = approve(1, "e3", rev2(), "REVIEWER");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.versionStatus()).isEqualTo("EFFECTIVE");
    }

    @Test
    void sameEventDifferentContentConflicts() {
        createDefaultVersion();
        approve(1, "evt-1", qa1(), "QA");
        assertThatThrownBy(() -> approve(1, "evt-1", rev1(), "REVIEWER"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EVENT_CONFLICT);
        assertThatThrownBy(() -> signingService.submit(docCode, 1, evt("evt-1"), qa1(), "QA", DecisionType.REJECT))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EVENT_CONFLICT);
    }

    @Test
    void signerWithoutRoleIsRejected() {
        createDefaultVersion();
        assertThatThrownBy(() -> approve(1, "e1", outsider(), "QA"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.ELIGIBILITY);
    }

    @Test
    void roleOutsidePolicyIsRejected() {
        createDefaultVersion();
        assertThatThrownBy(() -> approve(1, "e1", qa1(), "NOT_IN_POLICY"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.ELIGIBILITY);
    }

    @Test
    void newEffectiveVersionSupersedesPreviousOne() {
        documentService.createVersion(docCode, "v1-content", List.of(new PolicyRequirementView("QA", 1, true)));
        approve(1, "e1", qa1(), "QA");
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("EFFECTIVE");

        documentService.createVersion(docCode, "v2-content", List.of(new PolicyRequirementView("QA", 1, true)));
        approve(2, "e2", qa1(), "QA");

        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("SUPERSEDED");
        assertThat(documentService.getVersion(docCode, 2).status()).isEqualTo("EFFECTIVE");

        Long documentId = documentService.getDocument(docCode).id();
        EffectiveVersion effective = effectiveRepo.findById(documentId).orElseThrow();
        assertThat(effective.getVersion().getId())
                .isEqualTo(documentService.getVersion(docCode, 2).id());
    }

    @Test
    void versionNumbersStrictlyIncrease() {
        VersionView v1 = documentService.createVersion(docCode, "c1",
                List.of(new PolicyRequirementView("QA", 1, false)));
        VersionView v2 = documentService.createVersion(docCode, "c2",
                List.of(new PolicyRequirementView("QA", 1, false)));
        VersionView v3 = documentService.createVersion(docCode, "c3",
                List.of(new PolicyRequirementView("QA", 1, false)));
        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v3.versionNo()).isEqualTo(3);
        assertThat(documentService.listVersions(docCode))
                .extracting(VersionView::versionNo)
                .containsExactly(1, 2, 3);
    }

    @Test
    void auditListsAllDecisionsInOrder() {
        createDefaultVersion();
        approve(1, "e1", qa1(), "QA");
        approve(1, "e2", rev1(), "REVIEWER");
        signingService.submit(docCode, 1, evt("e3"), rev2(), "REVIEWER", DecisionType.REJECT);

        List<DecisionView> audit = documentService.listDecisions(docCode, 1);
        assertThat(audit).extracting(DecisionView::eventId)
                .containsExactly(evt("e1"), evt("e2"), evt("e3"));
        assertThat(audit).extracting(DecisionView::decision)
                .containsExactly("APPROVE", "APPROVE", "REJECT");
    }

    @Test
    void unknownSignerAndVersionReportNotFound() {
        createDefaultVersion();
        assertThatThrownBy(() -> approve(1, "e1", "ghost", "QA"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.SIGNER_NOT_FOUND);
        assertThatThrownBy(() -> approve(99, "e2", qa1(), "QA"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VERSION_NOT_FOUND);
    }
}
