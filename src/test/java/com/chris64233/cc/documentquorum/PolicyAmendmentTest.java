package com.chris64233.cc.documentquorum;

import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.EffectiveVersionRepository;
import com.chris64233.cc.documentquorum.service.AmendmentService;
import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.service.SignerService;
import com.chris64233.cc.documentquorum.service.SigningService;
import com.chris64233.cc.documentquorum.service.views.AmendmentView;
import com.chris64233.cc.documentquorum.service.views.AmendmentVoteResultView;
import com.chris64233.cc.documentquorum.service.views.CarryOverView;
import com.chris64233.cc.documentquorum.service.views.EffectivenessView;
import com.chris64233.cc.documentquorum.service.views.PolicyDiffView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import com.chris64233.cc.documentquorum.service.views.PolicyVersionView;
import com.chris64233.cc.documentquorum.service.views.ProgressView;
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
    EffectiveVersionRepository effectiveRepo;

    String docCode;

    @BeforeEach
    void setUp() {
        docCode = "DOC-" + UUID.randomUUID();
        documentService.createDocument(docCode, "策略修订测试文件");
    }

    private String suffix() {
        return docCode.substring(4, 12);
    }

    private String newSigner(String prefix, String... roles) {
        String externalId = prefix + "-" + suffix();
        signerService.createSigner(externalId, externalId, Set.of(roles));
        return externalId;
    }

    private String evt(String eventId) {
        return suffix() + "-" + eventId;
    }

    private void approve(int versionNo, String eventId, String signer, String role) {
        signingService.submit(docCode, versionNo, evt(eventId), signer, role, DecisionType.APPROVE);
    }

    private AmendmentView createAmendment(int versionNo, String amendmentNo,
                                          List<PolicyRequirementView> policy) {
        return amendmentService.createAmendment(docCode, versionNo, amendmentNo, policy);
    }

    private AmendmentVoteResultView vote(int versionNo, String amendmentNo, String eventId,
                                         String signer, DecisionType decision) {
        return amendmentService.vote(docCode, versionNo, amendmentNo, evt(eventId), signer, decision);
    }

    @Test
    void amendmentEnactsAtThresholdAndCarriesOverOnlyMatchingApprovals() {
        String qa1 = newSigner("qa1", "QA");
        String rev1 = newSigner("rev1", "REVIEWER");
        String rev2 = newSigner("rev2", "REVIEWER");
        String admin1 = newSigner("admin1", "ADMIN");
        String admin2 = newSigner("admin2", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, false),
                new PolicyRequirementView("REVIEWER", 3, false)), "ADMIN", 2);
        approve(1, "e1", qa1, "QA");
        approve(1, "e2", rev1, "REVIEWER");
        approve(1, "e3", rev2, "REVIEWER");
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("PENDING");

        // 修订：保留 REVIEWER（门槛降为 1），移除 QA
        createAmendment(1, "A1", List.of(new PolicyRequirementView("REVIEWER", 1, false)));
        AmendmentVoteResultView first = vote(1, "A1", "v1", admin1, DecisionType.APPROVE);
        assertThat(first.amendmentStatus()).isEqualTo("PENDING");
        AmendmentVoteResultView last = vote(1, "A1", "v2", admin2, DecisionType.APPROVE);
        assertThat(last.amendmentStatus()).isEqualTo("ENACTED");
        // 沿用的两票 REVIEWER 同意满足新门槛，版本直接生效
        assertThat(last.versionStatus()).isEqualTo("EFFECTIVE");

        AmendmentView amendment = amendmentService.getAmendment(docCode, 1, "A1");
        assertThat(amendment.status()).isEqualTo("ENACTED");
        assertThat(amendment.enactedPolicyVersionNo()).isEqualTo(2);
        assertThat(amendment.approvalCount()).isEqualTo(2);

        CarryOverView carryOver = documentService.getCarryOver(docCode, 1);
        assertThat(carryOver.currentPolicyVersionNo()).isEqualTo(2);
        assertThat(carryOver.decisions()).hasSize(3);
        assertThat(carryOver.decisions())
                .filteredOn(d -> d.role().equals("QA"))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.counted()).isFalse();
                    assertThat(d.reason()).isEqualTo("ROLE_NOT_IN_CURRENT_POLICY");
                });
        assertThat(carryOver.decisions())
                .filteredOn(d -> d.role().equals("REVIEWER"))
                .allSatisfy(d -> {
                    assertThat(d.counted()).isTrue();
                    assertThat(d.reason()).isEqualTo("CARRIED_OVER");
                });

        // 审计保留全部决定，包括不再计数的 QA 同意
        assertThat(documentService.listDecisions(docCode, 1)).hasSize(3);

        EffectivenessView effectiveness = documentService.getEffectiveness(docCode, 1);
        assertThat(effectiveness.effectivePolicyVersionNo()).isEqualTo(2);
        assertThat(effectiveness.activatedAt()).isNotNull();
        assertThat(effectiveness.requirements())
                .containsExactly(new PolicyRequirementView("REVIEWER", 1, false));
        assertThat(effectiveness.countedDecisions()).hasSize(2)
                .allSatisfy(d -> assertThat(d.role()).isEqualTo("REVIEWER"));
    }

    @Test
    void rejectedVersionCannotBeRevivedByAmendment() {
        String qa1 = newSigner("qa1", "QA");
        String admin = newSigner("admin", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, true)), "ADMIN", 1);
        signingService.submit(docCode, 1, evt("e1"), qa1, "QA", DecisionType.REJECT);
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("REJECTED");

        // 已被明确拒绝的版本不能通过降低门槛的修订重新生效
        assertThatThrownBy(() -> createAmendment(1, "A1",
                List.of(new PolicyRequirementView("QA", 1, false))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.TERMINAL_STATE);
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("REJECTED");
    }

    @Test
    void effectiveVersionCannotBeAmended() {
        String qa1 = newSigner("qa1", "QA");
        newSigner("admin", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, false)), "ADMIN", 1);
        approve(1, "e1", qa1, "QA");
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("EFFECTIVE");

        assertThatThrownBy(() -> createAmendment(1, "A1",
                List.of(new PolicyRequirementView("QA", 2, false))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.TERMINAL_STATE);
    }

    @Test
    void amendmentCreationIsIdempotentByAmendmentNo() {
        newSigner("admin", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, false)), "ADMIN", 1);
        List<PolicyRequirementView> policy = List.of(new PolicyRequirementView("QA", 2, true));

        AmendmentView created = createAmendment(1, "A1", policy);
        assertThat(created.replayed()).isFalse();
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.basePolicyVersionNo()).isEqualTo(1);
        assertThat(created.amendmentRole()).isEqualTo("ADMIN");
        assertThat(created.amendmentThreshold()).isEqualTo(1);

        AmendmentView replay = createAmendment(1, "A1", policy);
        assertThat(replay.replayed()).isTrue();
        assertThat(amendmentService.listAmendments(docCode, 1)).hasSize(1);

        assertThatThrownBy(() -> createAmendment(1, "A1",
                List.of(new PolicyRequirementView("QA", 3, false))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EVENT_CONFLICT);
    }

    @Test
    void amendmentVoteIsIdempotentAndGuardsEligibility() {
        String admin1 = newSigner("admin1", "ADMIN");
        String admin2 = newSigner("admin2", "ADMIN");
        String outsider = newSigner("outsider", "QA");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, false)), "ADMIN", 2);
        createAmendment(1, "A1", List.of(new PolicyRequirementView("QA", 2, false)));

        // 非管理角色不能投票
        assertThatThrownBy(() -> vote(1, "A1", "v0", outsider, DecisionType.APPROVE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.ELIGIBILITY);

        vote(1, "A1", "v1", admin1, DecisionType.APPROVE);
        // 相同事件号相同内容重放
        AmendmentVoteResultView replay = vote(1, "A1", "v1", admin1, DecisionType.APPROVE);
        assertThat(replay.replayed()).isTrue();
        // 相同事件号不同内容冲突
        assertThatThrownBy(() -> vote(1, "A1", "v1", admin2, DecisionType.APPROVE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.EVENT_CONFLICT);
        // 同一签署人只能投一票
        assertThatThrownBy(() -> vote(1, "A1", "v2", admin1, DecisionType.APPROVE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.DUPLICATE_DECISION);

        AmendmentView amendment = amendmentService.getAmendment(docCode, 1, "A1");
        assertThat(amendment.votes()).hasSize(1);
        assertThat(amendment.approvalCount()).isEqualTo(1);
    }

    @Test
    void staleAmendmentCannotBeEnactedAfterAnotherAmendmentEnacts() {
        String admin = newSigner("admin", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, false)), "ADMIN", 1);
        createAmendment(1, "A1", List.of(new PolicyRequirementView("QA", 2, false)));
        createAmendment(1, "A2", List.of(new PolicyRequirementView("QA", 3, false)));

        vote(1, "A1", "v1", admin, DecisionType.APPROVE);
        assertThat(amendmentService.getAmendment(docCode, 1, "A1").status()).isEqualTo("ENACTED");
        assertThat(amendmentService.getAmendment(docCode, 1, "A2").status()).isEqualTo("STALE");

        assertThatThrownBy(() -> vote(1, "A2", "v2", admin, DecisionType.APPROVE))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.TERMINAL_STATE);
    }

    @Test
    void amendmentRequiresAdminRoleInOriginalPolicy() {
        documentService.createVersion(docCode, "v1",
                List.of(new PolicyRequirementView("QA", 1, false)));
        assertThatThrownBy(() -> createAmendment(1, "A1",
                List.of(new PolicyRequirementView("QA", 2, false))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VALIDATION);
    }

    @Test
    void policyDiffAndPolicyVersionListingReflectEnactment() {
        String admin = newSigner("admin", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, true),
                new PolicyRequirementView("REVIEWER", 2, false)), "ADMIN", 1);
        createAmendment(1, "A1", List.of(
                new PolicyRequirementView("QA", 2, false),
                new PolicyRequirementView("LEGAL", 1, true)));
        vote(1, "A1", "v1", admin, DecisionType.APPROVE);

        List<PolicyVersionView> policies = documentService.listPolicyVersions(docCode, 1);
        assertThat(policies).hasSize(2);
        assertThat(policies.get(0).active()).isFalse();
        assertThat(policies.get(0).sourceAmendmentNo()).isNull();
        assertThat(policies.get(1).active()).isTrue();
        assertThat(policies.get(1).sourceAmendmentNo()).isEqualTo("A1");
        assertThat(policies.get(1).amendmentRole()).isEqualTo("ADMIN");
        assertThat(policies.get(1).amendmentThreshold()).isEqualTo(1);

        PolicyDiffView diff = documentService.getPolicyDiff(docCode, 1, 1, 2);
        assertThat(diff.changes()).hasSize(3);
        assertThat(diff.changes()).filteredOn(c -> c.role().equals("QA")).singleElement()
                .satisfies(c -> {
                    assertThat(c.changeType()).isEqualTo("MODIFIED");
                    assertThat(c.oldRequiredApprovals()).isEqualTo(1);
                    assertThat(c.newRequiredApprovals()).isEqualTo(2);
                    assertThat(c.oldVetoPower()).isTrue();
                    assertThat(c.newVetoPower()).isFalse();
                });
        assertThat(diff.changes()).filteredOn(c -> c.role().equals("REVIEWER")).singleElement()
                .satisfies(c -> assertThat(c.changeType()).isEqualTo("REMOVED"));
        assertThat(diff.changes()).filteredOn(c -> c.role().equals("LEGAL")).singleElement()
                .satisfies(c -> assertThat(c.changeType()).isEqualTo("ADDED"));

        assertThatThrownBy(() -> documentService.getPolicyDiff(docCode, 1, 1, 9))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.POLICY_VERSION_NOT_FOUND);
    }

    @Test
    void progressCountsOnlyCarriedOverApprovalsUnderNewPolicy() {
        String qa1 = newSigner("qa1", "QA");
        String rev1 = newSigner("rev1", "REVIEWER");
        String admin = newSigner("admin", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, false),
                new PolicyRequirementView("REVIEWER", 2, false)), "ADMIN", 1);
        approve(1, "e1", qa1, "QA");
        approve(1, "e2", rev1, "REVIEWER");

        // 新策略保留两个角色但提高 QA 门槛：已有 QA 同意仍沿用但不再满足
        createAmendment(1, "A1", List.of(
                new PolicyRequirementView("QA", 2, false),
                new PolicyRequirementView("REVIEWER", 2, false)));
        vote(1, "A1", "v1", admin, DecisionType.APPROVE);

        ProgressView progress = documentService.getProgress(docCode, 1);
        assertThat(progress.policyVersionNo()).isEqualTo(2);
        assertThat(progress.status()).isEqualTo("PENDING");
        assertThat(progress.roles()).filteredOn(r -> r.role().equals("QA")).singleElement()
                .satisfies(r -> {
                    assertThat(r.approvedCount()).isEqualTo(1);
                    assertThat(r.requiredApprovals()).isEqualTo(2);
                    assertThat(r.met()).isFalse();
                });
        assertThat(progress.roles()).filteredOn(r -> r.role().equals("REVIEWER")).singleElement()
                .satisfies(r -> {
                    assertThat(r.approvedCount()).isEqualTo(1);
                    assertThat(r.met()).isFalse();
                });
    }

    @Test
    void newDecisionsAreEvaluatedAgainstCurrentPolicyAfterEnactment() {
        String qa1 = newSigner("qa1", "QA");
        String legal1 = newSigner("legal1", "LEGAL");
        String admin = newSigner("admin", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, false)), "ADMIN", 1);
        createAmendment(1, "A1", List.of(new PolicyRequirementView("LEGAL", 1, false)));
        vote(1, "A1", "v1", admin, DecisionType.APPROVE);

        // 旧策略角色已不在新策略中
        assertThatThrownBy(() -> approve(1, "e1", qa1, "QA"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.ELIGIBILITY);
        // 新策略角色可签署并生效
        approve(1, "e2", legal1, "LEGAL");
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("EFFECTIVE");
        assertThat(documentService.getEffectiveness(docCode, 1).effectivePolicyVersionNo()).isEqualTo(2);
    }

    @Test
    void effectivenessQueryFailsForNonEffectiveVersion() {
        documentService.createVersion(docCode, "v1",
                List.of(new PolicyRequirementView("QA", 1, false)));
        assertThatThrownBy(() -> documentService.getEffectiveness(docCode, 1))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.VERSION_NOT_EFFECTIVE);
    }

    @Test
    void quorumOnOlderVersionDoesNotSupersedeNewerEffectiveVersion() {
        String qa1 = newSigner("qa1", "QA");
        String qa2 = newSigner("qa2", "QA");
        documentService.createVersion(docCode, "v1",
                List.of(new PolicyRequirementView("QA", 2, false)));
        documentService.createVersion(docCode, "v2",
                List.of(new PolicyRequirementView("QA", 1, false)));
        approve(2, "e1", qa1, "QA");
        assertThat(documentService.getVersion(docCode, 2).status()).isEqualTo("EFFECTIVE");

        approve(1, "e2", qa1, "QA");
        approve(1, "e3", qa2, "QA");

        // 较旧版本达到法定人数也不能取代较新的生效版本
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("SUPERSEDED");
        assertThat(documentService.getVersion(docCode, 2).status()).isEqualTo("EFFECTIVE");
        Long documentId = documentService.getDocument(docCode).id();
        assertThat(effectiveRepo.findById(documentId).orElseThrow().getVersion().getId())
                .isEqualTo(documentService.getVersion(docCode, 2).id());
    }
}
