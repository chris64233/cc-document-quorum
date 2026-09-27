package com.chris64233.cc.documentquorum;

import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.domain.EffectiveVersion;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.service.AmendmentService;
import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.service.SignerService;
import com.chris64233.cc.documentquorum.service.SigningService;
import com.chris64233.cc.documentquorum.service.views.AmendmentResultView;
import com.chris64233.cc.documentquorum.service.views.AmendmentView;
import com.chris64233.cc.documentquorum.service.views.CarryOverView;
import com.chris64233.cc.documentquorum.service.views.DecisionView;
import com.chris64233.cc.documentquorum.service.views.EffectiveBasisView;
import com.chris64233.cc.documentquorum.service.views.PolicyDiffView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import com.chris64233.cc.documentquorum.service.views.PolicyVersionView;
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
class PolicyAmendmentTest {

    @Autowired
    DocumentService documentService;
    @Autowired
    SigningService signingService;
    @Autowired
    AmendmentService amendmentService;
    @Autowired
    SignerService signerService;
    @Autowired
    com.chris64233.cc.documentquorum.repo.EffectiveVersionRepository effectiveRepo;

    String docCode;
    String suffix;
    String qa1;
    String rev1;
    String rev2;
    String admin1;
    String admin2;

    @BeforeEach
    void setUp() {
        docCode = "DOC-" + UUID.randomUUID();
        suffix = docCode.substring(4, 12);
        documentService.createDocument(docCode, "受控文件");
        qa1 = newSigner("qa1", "QA");
        rev1 = newSigner("rev1", "REVIEWER");
        rev2 = newSigner("rev2", "REVIEWER");
        admin1 = newSigner("admin1", "ADMIN");
        admin2 = newSigner("admin2", "ADMIN");
    }

    private String newSigner(String prefix, String role) {
        String externalId = prefix + "-" + suffix;
        signerService.createSigner(externalId, externalId, Set.of(role));
        return externalId;
    }

    private String evt(String eventId) {
        return suffix + "-" + eventId;
    }

    private List<PolicyRequirementView> policy(Object... roleRequiredVeto) {
        java.util.List<PolicyRequirementView> list = new java.util.ArrayList<>();
        for (int i = 0; i < roleRequiredVeto.length; i += 3) {
            list.add(new PolicyRequirementView((String) roleRequiredVeto[i],
                    (int) roleRequiredVeto[i + 1], (boolean) roleRequiredVeto[i + 2]));
        }
        return list;
    }

    private VersionView createAmendableVersion(List<PolicyRequirementView> policy) {
        return documentService.createVersion(docCode, "v-content", policy, "ADMIN", 2);
    }

    private VersionView createAmendableVersion(List<PolicyRequirementView> policy, int amendThreshold) {
        return documentService.createVersion(docCode, "v-content", policy, "ADMIN", amendThreshold);
    }

    private AmendmentResultView vote(int amendmentNo, String eventId, String signer, DecisionType decision) {
        return amendmentService.vote(docCode, 1, amendmentNo, evt(eventId), signer, decision);
    }

    @Test
    void amendmentEnactsNewPolicyAndCarriesOverValidApprovals() {
        createAmendableVersion(policy("QA", 1, false, "REVIEWER", 2, false));
        signingService.submit(docCode, 1, evt("d1"), qa1, "QA", DecisionType.APPROVE);
        signingService.submit(docCode, 1, evt("d2"), rev1, "REVIEWER", DecisionType.APPROVE);

        AmendmentView created = amendmentService.createAmendment(docCode, 1, 1,
                policy("QA", 1, false, "REVIEWER", 1, false));
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.replayed()).isFalse();
        assertThat(created.basePolicyNo()).isEqualTo(1);
        assertThat(created.policyNo()).isEqualTo(2);
        assertThat(created.approvalCount()).isZero();
        assertThat(created.requiredApprovals()).isEqualTo(2);

        assertThat(documentService.getProgress(docCode, 1).policyNo()).isEqualTo(1);

        assertThat(vote(1, "a1", admin1, DecisionType.APPROVE).effectiveNow()).isFalse();
        AmendmentResultView finalVote = vote(1, "a2", admin2, DecisionType.APPROVE);
        assertThat(finalVote.status()).isEqualTo("EFFECTIVE");
        assertThat(finalVote.effectiveNow()).isTrue();
        assertThat(finalVote.effectivePolicyNo()).isEqualTo(2);
        assertThat(finalVote.carriedOverCount()).isEqualTo(2);
        assertThat(finalVote.voidedCount()).isZero();

        ProgressView progress = documentService.getProgress(docCode, 1);
        assertThat(progress.policyNo()).isEqualTo(2);
        assertThat(progress.status()).isEqualTo("EFFECTIVE");
        assertThat(progress.roles()).allSatisfy(role -> assertThat(role.met()).isTrue());

        EffectiveBasisView basis = documentService.getEffectiveBasis(docCode, 1);
        assertThat(basis.basis()).isEqualTo("AMENDED_POLICY_QUORUM");
        assertThat(basis.policyNo()).isEqualTo(2);
        assertThat(basis.amendmentNo()).isEqualTo(1);

        List<CarryOverView> carryOver = amendmentService.listCarryOver(docCode, 1, 1);
        assertThat(carryOver).hasSize(2);
        assertThat(carryOver).allSatisfy(c -> {
            assertThat(c.outcome()).isEqualTo("CARRIED_OVER");
            assertThat(c.reason()).isNull();
        });
        assertThat(documentService.listDecisions(docCode, 1))
                .allSatisfy(d -> assertThat(d.voidedPolicyNo()).isNull());
    }

    @Test
    void approvalOnRemovedRoleIsVoidedButAuditKept() {
        createAmendableVersion(policy("QA", 1, false, "REVIEWER", 2, false), 1);
        signingService.submit(docCode, 1, evt("d1"), qa1, "QA", DecisionType.APPROVE);
        signingService.submit(docCode, 1, evt("d2"), rev1, "REVIEWER", DecisionType.APPROVE);

        amendmentService.createAmendment(docCode, 1, 1, policy("QA", 1, false));
        AmendmentResultView result = vote(1, "a1", admin1, DecisionType.APPROVE);
        assertThat(result.effectiveNow()).isTrue();
        assertThat(result.carriedOverCount()).isEqualTo(1);
        assertThat(result.voidedCount()).isEqualTo(1);

        List<CarryOverView> carryOver = amendmentService.listCarryOver(docCode, 1, 1);
        assertThat(carryOver).filteredOn(c -> c.outcome().equals("VOIDED")).hasSize(1)
                .first().satisfies(c -> {
                    assertThat(c.role()).isEqualTo("REVIEWER");
                    assertThat(c.reason()).isEqualTo("ROLE_NOT_IN_NEW_POLICY");
                });

        List<DecisionView> decisions = documentService.listDecisions(docCode, 1);
        DecisionView voided = decisions.stream().filter(d -> d.role().equals("REVIEWER")).findFirst().orElseThrow();
        assertThat(voided.voidedPolicyNo()).isEqualTo(2);
        DecisionView carried = decisions.stream().filter(d -> d.role().equals("QA")).findFirst().orElseThrow();
        assertThat(carried.voidedPolicyNo()).isNull();

        // 失效决定保留审计但不计入进度
        ProgressView progress = documentService.getProgress(docCode, 1);
        assertThat(progress.roles()).hasSize(1);
        assertThat(progress.roles().get(0).role()).isEqualTo("QA");
        assertThat(progress.roles().get(0).approvedCount()).isEqualTo(1);
        assertThat(progress.status()).isEqualTo("EFFECTIVE");
    }

    @Test
    void approvalVoidedWhenSignerNoLongerHasRole() {
        createAmendableVersion(policy("QA", 1, false, "REVIEWER", 2, false), 1);
        signingService.submit(docCode, 1, evt("d1"), qa1, "QA", DecisionType.APPROVE);
        signingService.submit(docCode, 1, evt("d2"), rev1, "REVIEWER", DecisionType.APPROVE);

        signerService.updateRoles(rev1, Set.of("OTHER"));

        amendmentService.createAmendment(docCode, 1, 1,
                policy("QA", 1, false, "REVIEWER", 2, false));
        AmendmentResultView result = vote(1, "a1", admin1, DecisionType.APPROVE);
        assertThat(result.carriedOverCount()).isEqualTo(1);
        assertThat(result.voidedCount()).isEqualTo(1);

        CarryOverView voided = amendmentService.listCarryOver(docCode, 1, 1).stream()
                .filter(c -> c.outcome().equals("VOIDED"))
                .findFirst().orElseThrow();
        assertThat(voided.signerExternalId()).isEqualTo(rev1);
        assertThat(voided.reason()).isEqualTo("SIGNER_LOST_ROLE");

        ProgressView progress = documentService.getProgress(docCode, 1);
        assertThat(progress.policyNo()).isEqualTo(2);
        assertThat(progress.status()).isEqualTo("PENDING");
        assertThat(progress.roles()).filteredOn(r -> r.role().equals("REVIEWER"))
                .singleElement().satisfies(r -> {
                    assertThat(r.approvedCount()).isZero();
                    assertThat(r.met()).isFalse();
                });
    }

    @Test
    void rejectedDecisionsAreNeverCarriedOver() {
        createAmendableVersion(policy("QA", 1, false, "REVIEWER", 1, false), 1);
        signingService.submit(docCode, 1, evt("d1"), rev1, "REVIEWER", DecisionType.REJECT);
        signingService.submit(docCode, 1, evt("d2"), qa1, "QA", DecisionType.APPROVE);

        amendmentService.createAmendment(docCode, 1, 1, policy("QA", 1, false));
        AmendmentResultView result = vote(1, "a1", admin1, DecisionType.APPROVE);
        assertThat(result.carriedOverCount()).isEqualTo(1);
        assertThat(result.voidedCount()).isEqualTo(1);
        assertThat(amendmentService.listCarryOver(docCode, 1, 1))
                .filteredOn(c -> c.decision().equals("REJECT"))
                .singleElement().satisfies(c -> {
                    assertThat(c.outcome()).isEqualTo("VOIDED");
                    assertThat(c.reason()).isEqualTo("REJECT_NOT_CARRIED");
                });
    }

    @Test
    void rejectedVersionCannotBeAmendedEvenByLoweringThresholds() {
        createAmendableVersion(policy("QA", 1, true, "REVIEWER", 2, false));
        signingService.submit(docCode, 1, evt("d1"), qa1, "QA", DecisionType.REJECT);
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("REJECTED");

        assertThatThrownBy(() -> amendmentService.createAmendment(docCode, 1, 1,
                policy("QA", 1, true, "REVIEWER", 1, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.TERMINAL_STATE);
    }

    @Test
    void effectiveVersionCannotBeAmended() {
        createAmendableVersion(policy("QA", 1, false));
        signingService.submit(docCode, 1, evt("d1"), qa1, "QA", DecisionType.APPROVE);
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("EFFECTIVE");

        assertThatThrownBy(() -> amendmentService.createAmendment(docCode, 1, 1,
                policy("QA", 1, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.TERMINAL_STATE);
    }

    @Test
    void versionWithoutAmendRuleCannotBeAmended() {
        documentService.createVersion(docCode, "v-content", policy("QA", 1, false));
        assertThatThrownBy(() -> amendmentService.createAmendment(docCode, 1, 1,
                policy("QA", 1, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION);
    }

    @Test
    void amendmentCreationIsIdempotentByAmendmentNo() {
        createAmendableVersion(policy("QA", 1, false, "REVIEWER", 2, false));

        AmendmentView first = amendmentService.createAmendment(docCode, 1, 7,
                policy("QA", 1, false, "REVIEWER", 1, false));
        assertThat(first.replayed()).isFalse();
        assertThat(first.amendmentNo()).isEqualTo(7);

        AmendmentView replay = amendmentService.createAmendment(docCode, 1, 7,
                policy("REVIEWER", 1, false, "QA", 1, false));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.policyNo()).isEqualTo(first.policyNo());
        assertThat(amendmentService.listAmendments(docCode, 1)).hasSize(1);

        assertThatThrownBy(() -> amendmentService.createAmendment(docCode, 1, 7,
                policy("QA", 2, false)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.AMENDMENT_CONFLICT);
    }

    @Test
    void amendmentVoteIsIdempotentByEventId() {
        createAmendableVersion(policy("QA", 1, false, "REVIEWER", 2, false));
        amendmentService.createAmendment(docCode, 1, 1,
                policy("QA", 1, false, "REVIEWER", 1, false));

        AmendmentResultView first = vote(1, "a1", admin1, DecisionType.APPROVE);
        assertThat(first.replayed()).isFalse();
        assertThat(first.status()).isEqualTo("PENDING");

        AmendmentResultView replay = vote(1, "a1", admin1, DecisionType.APPROVE);
        assertThat(replay.replayed()).isTrue();

        assertThatThrownBy(() -> vote(1, "a1", admin1, DecisionType.REJECT))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EVENT_CONFLICT);
        assertThatThrownBy(() -> vote(1, "a1", admin2, DecisionType.APPROVE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EVENT_CONFLICT);
        assertThatThrownBy(() -> vote(1, "a2", admin1, DecisionType.APPROVE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_DECISION);
    }

    @Test
    void onlyAdminRoleCanVoteForAmendment() {
        createAmendableVersion(policy("QA", 1, false, "REVIEWER", 2, false));
        amendmentService.createAmendment(docCode, 1, 1,
                policy("QA", 1, false, "REVIEWER", 1, false));

        assertThatThrownBy(() -> vote(1, "a1", qa1, DecisionType.APPROVE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.ELIGIBILITY);
    }

    @Test
    void voteRejectedAfterVersionTerminalButEventReplayStillWorks() {
        createAmendableVersion(policy("QA", 1, true, "REVIEWER", 1, false));
        amendmentService.createAmendment(docCode, 1, 1, policy("QA", 1, true, "REVIEWER", 1, false));
        signingService.submit(docCode, 1, evt("d1"), qa1, "QA", DecisionType.REJECT);

        assertThatThrownBy(() -> vote(1, "a1", admin1, DecisionType.APPROVE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.TERMINAL_STATE);
    }

    @Test
    void policyVersionHistoryAndDiffAreQueryable() {
        createAmendableVersion(policy("QA", 1, true, "REVIEWER", 2, false));
        amendmentService.createAmendment(docCode, 1, 1,
                policy("QA", 2, true, "LEGAL", 1, false));
        vote(1, "a1", admin1, DecisionType.APPROVE);
        vote(1, "a2", admin2, DecisionType.APPROVE);

        List<PolicyVersionView> versions = documentService.listPolicyVersions(docCode, 1);
        assertThat(versions).extracting(PolicyVersionView::policyNo).containsExactly(1, 2);
        assertThat(versions).extracting(PolicyVersionView::status)
                .containsExactly("SUPERSEDED", "ACTIVE");

        PolicyDiffView diff = documentService.diffPolicies(docCode, 1, 1, 2);
        assertThat(diff.changes()).hasSize(3);
        assertThat(diff.changes()).filteredOn(c -> c.role().equals("QA"))
                .singleElement().satisfies(c -> {
                    assertThat(c.changeType()).isEqualTo("CHANGED");
                    assertThat(c.oldRequiredApprovals()).isEqualTo(1);
                    assertThat(c.newRequiredApprovals()).isEqualTo(2);
                    assertThat(c.oldVetoPower()).isTrue();
                    assertThat(c.newVetoPower()).isTrue();
                });
        assertThat(diff.changes()).filteredOn(c -> c.changeType().equals("REMOVED"))
                .singleElement().satisfies(c -> assertThat(c.role()).isEqualTo("REVIEWER"));
        assertThat(diff.changes()).filteredOn(c -> c.changeType().equals("ADDED"))
                .singleElement().satisfies(c -> assertThat(c.role()).isEqualTo("LEGAL"));

        // 相同策略版本对比没有差异
        assertThat(documentService.diffPolicies(docCode, 1, 1, 1).changes()).isEmpty();
    }

    @Test
    void progressAndVersionViewCarryActivePolicyNumber() {
        createAmendableVersion(policy("QA", 1, false, "REVIEWER", 2, false));
        VersionView view = documentService.getVersion(docCode, 1);
        assertThat(view.activePolicyNo()).isEqualTo(1);
        assertThat(view.amendRole()).isEqualTo("ADMIN");
        assertThat(view.amendRequiredApprovals()).isEqualTo(2);

        amendmentService.createAmendment(docCode, 1, 1,
                policy("QA", 1, false, "REVIEWER", 1, false));
        // 修订生效前仍按 1 号策略计算
        assertThat(documentService.getProgress(docCode, 1).policyNo()).isEqualTo(1);
        vote(1, "a1", admin1, DecisionType.APPROVE);
        assertThat(documentService.getProgress(docCode, 1).policyNo()).isEqualTo(1);
        vote(1, "a2", admin2, DecisionType.APPROVE);
        assertThat(documentService.getProgress(docCode, 1).policyNo()).isEqualTo(2);
    }

    @Test
    void lateQuorumOnOlderVersionCannotDisplaceNewerEffectiveVersion() {
        documentService.createVersion(docCode, "v1", policy("QA", 1, false));
        documentService.createVersion(docCode, "v2", policy("QA", 1, false));

        signingService.submit(docCode, 2, evt("d2"), qa1, "QA", DecisionType.APPROVE);
        com.chris64233.cc.documentquorum.service.views.DecisionResultView late =
                signingService.submit(docCode, 1, evt("d1"), qa1, "QA", DecisionType.APPROVE);

        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("SUPERSEDED");
        assertThat(documentService.getVersion(docCode, 2).status()).isEqualTo("EFFECTIVE");
        assertThat(late.versionStatus()).isEqualTo("SUPERSEDED");

        Long documentId = documentService.getDocument(docCode).id();
        EffectiveVersion effective = effectiveRepo.findById(documentId).orElseThrow();
        assertThat(effective.getVersion().getId())
                .isEqualTo(documentService.getVersion(docCode, 2).id());
    }
}
