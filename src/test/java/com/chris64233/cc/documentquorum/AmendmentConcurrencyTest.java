package com.chris64233.cc.documentquorum;

import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.service.AmendmentService;
import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.service.SignerService;
import com.chris64233.cc.documentquorum.service.SigningService;
import com.chris64233.cc.documentquorum.service.views.AmendmentVoteResultView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import com.chris64233.cc.documentquorum.service.views.VersionView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AmendmentConcurrencyTest {

    @Autowired
    DocumentService documentService;
    @Autowired
    SigningService signingService;
    @Autowired
    AmendmentService amendmentService;
    @Autowired
    SignerService signerService;

    private String newDoc() {
        String docCode = "DOC-" + UUID.randomUUID();
        documentService.createDocument(docCode, "修订并发测试文件");
        return docCode;
    }

    private String newSigner(String prefix, String... roles) {
        String externalId = prefix + "-" + UUID.randomUUID();
        signerService.createSigner(externalId, externalId, Set.of(roles));
        return externalId;
    }

    private void awaitGateAndRun(CountDownLatch gate, ConcurrentLinkedQueue<Throwable> errors,
                                 java.util.concurrent.Callable<Object> task) {
        try {
            gate.await(10, TimeUnit.SECONDS);
            task.call();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Throwable ex) {
            errors.add(ex);
        }
    }

    /**
     * 修订最后一票与普通签署最后一票并发：版本行悲观锁把两者串行化，
     * 生效依据固定为一个明确的策略版本，不能出现版本同时按两套策略生效。
     */
    @Test
    void amendmentEnactAndFinalSignatureRunConcurrentlyWithSinglePolicyOutcome() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(8);
        // 原策略：QA 门槛 2（已有 1 票，差一票）；修订：QA 门槛降为 1（沿用即满足）
        String qaA = newSigner("qaA", "QA");
        String qaB = newSigner("qaB", "QA");
        String adm1 = newSigner("adm1", "ADMIN");
        String adm2 = newSigner("adm2", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 2, false)), "ADMIN", 2);
        signingService.submit(docCode, 1, run + "-e0", qaA, "QA", DecisionType.APPROVE);
        amendmentService.createAmendment(docCode, 1, "A-" + run,
                List.of(new PolicyRequirementView("QA", 1, false)));
        amendmentService.vote(docCode, 1, "A-" + run, run + "-v0", adm1, DecisionType.APPROVE);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> voteOutcomes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> signOutcomes = new ConcurrentLinkedQueue<>();
        Future<?> f1 = pool.submit(() -> awaitGateAndRun(gate, errors, () -> {
            try {
                AmendmentVoteResultView r = amendmentService.vote(docCode, 1, "A-" + run,
                        run + "-v1", adm2, DecisionType.APPROVE);
                voteOutcomes.add("OK:" + r.versionStatus() + ":policy" + documentService
                        .getVersion(docCode, 1).currentPolicyVersionNo());
            } catch (BusinessException ex) {
                voteOutcomes.add("THREW:" + ex.getCode());
            }
            return null;
        }));
        Future<?> f2 = pool.submit(() -> awaitGateAndRun(gate, errors, () -> {
            try {
                signOutcomes.add("OK:" + signingService.submit(docCode, 1, run + "-e1", qaB, "QA",
                        DecisionType.APPROVE).versionStatus());
            } catch (BusinessException ex) {
                signOutcomes.add("THREW:" + ex.getCode());
            }
            return null;
        }));
        gate.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(errors).isEmpty();
        VersionView version = documentService.getVersion(docCode, 1);
        assertThat(version.status()).isEqualTo("EFFECTIVE");
        int effectivePolicy = documentService.getEffectiveness(docCode, 1).effectivePolicyVersionNo();
        assertThat(effectivePolicy).isIn(1, 2);
        // 版本行锁串行化：恰好一个操作按明确策略版本完成生效，另一个遇到终态
        if (effectivePolicy == 2) {
            assertThat(version.currentPolicyVersionNo()).isEqualTo(2);
            assertThat(voteOutcomes).singleElement().asString().isEqualTo("OK:EFFECTIVE:policy2");
            assertThat(signOutcomes).singleElement().asString().isEqualTo("THREW:TERMINAL_STATE");
        } else {
            assertThat(version.currentPolicyVersionNo()).isEqualTo(1);
            assertThat(signOutcomes).singleElement().asString().isEqualTo("OK:EFFECTIVE");
            assertThat(voteOutcomes).singleElement().asString().isEqualTo("THREW:TERMINAL_STATE");
            // 修订未生效
            assertThat(amendmentService.getAmendment(docCode, 1, "A-" + run).status())
                    .isEqualTo("PENDING");
        }
    }

    /**
     * 修订生效与新文件版本的生效切换并发/交错：旧版本即使在新策略下达成，
     * 也不能错误取代较新的生效版本，只能进入 SUPERSEDED。
     */
    @Test
    void amendmentEnactDoesNotSupersedeNewerVersion() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(8);
        String qa1 = newSigner("qa1", "QA");
        String qa2 = newSigner("qa2", "QA");
        String adm1 = newSigner("adm1", "ADMIN");
        String adm2 = newSigner("adm2", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 2, false)), "ADMIN", 2);
        signingService.submit(docCode, 1, run + "-e0", qa1, "QA", DecisionType.APPROVE);
        amendmentService.createAmendment(docCode, 1, "A-" + run,
                List.of(new PolicyRequirementView("QA", 1, false)));
        amendmentService.vote(docCode, 1, "A-" + run, run + "-v0", adm1, DecisionType.APPROVE);

        // v2 先创建并生效
        documentService.createVersion(docCode, "v2",
                List.of(new PolicyRequirementView("QA", 1, false)));
        signingService.submit(docCode, 2, run + "-e2", qa2, "QA", DecisionType.APPROVE);
        assertThat(documentService.getVersion(docCode, 1).status()).isEqualTo("PENDING");

        // v1 的修订最后一票：沿用票满足新门槛，但 v2 已生效，v1 只能 SUPERSEDED
        AmendmentVoteResultView result = amendmentService.vote(docCode, 1, "A-" + run,
                run + "-v1", adm2, DecisionType.APPROVE);
        assertThat(result.amendmentStatus()).isEqualTo("ENACTED");
        assertThat(result.versionStatus()).isEqualTo("SUPERSEDED");

        List<VersionView> versions = documentService.listVersions(docCode);
        assertThat(versions).filteredOn(v -> v.versionNo() == 1).singleElement()
                .satisfies(v -> {
                    assertThat(v.status()).isEqualTo("SUPERSEDED");
                    assertThat(v.currentPolicyVersionNo()).isEqualTo(2);
                });
        assertThat(versions).filteredOn(v -> v.versionNo() == 2).singleElement()
                .satisfies(v -> assertThat(v.status()).isEqualTo("EFFECTIVE"));
    }

    /**
     * 同一修订的最后一票并发提交：只有一票生效，修订只 enactment 一次。
     */
    @Test
    void concurrentFinalVotesEnactAmendmentExactlyOnce() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(8);
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, false)), "ADMIN", 1);

        int admins = 4;
        List<String> adminIds = new java.util.ArrayList<>();
        for (int i = 0; i < admins; i++) {
            adminIds.add(newSigner("adm" + i, "ADMIN"));
        }
        amendmentService.createAmendment(docCode, 1, "A-" + run,
                List.of(new PolicyRequirementView("QA", 2, true)));

        ExecutorService pool = Executors.newFixedThreadPool(admins);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<AmendmentVoteResultView> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> codes = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < admins; i++) {
            String signer = adminIds.get(i);
            String event = run + "-v" + i;
            futures.add(pool.submit(() -> awaitGateAndRun(gate, errors, () -> {
                try {
                    results.add(amendmentService.vote(docCode, 1, "A-" + run, event, signer,
                            DecisionType.APPROVE));
                } catch (BusinessException ex) {
                    codes.add(ex.getCode());
                }
                return null;
            })));
        }
        gate.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(1);
        assertThat(results.peek().amendmentStatus()).isEqualTo("ENACTED");
        assertThat(codes).hasSize(admins - 1).allMatch(c -> c == ErrorCode.TERMINAL_STATE);
        assertThat(documentService.listPolicyVersions(docCode, 1)).hasSize(2);
    }

    /**
     * 修订号并发创建：相同修订号相同内容只有一个修订，其余幂等重放。
     */
    @Test
    void concurrentAmendmentCreationWithSameNoIsIdempotent() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(8);
        newSigner("adm-" + run, "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(
                new PolicyRequirementView("QA", 1, false)), "ADMIN", 1);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Boolean> replays = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> awaitGateAndRun(gate, errors, () -> {
                replays.add(amendmentService.createAmendment(docCode, 1, "A-" + run,
                        List.of(new PolicyRequirementView("QA", 2, false))).replayed());
                return null;
            })));
        }
        gate.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(errors).isEmpty();
        assertThat(replays).hasSize(threads);
        assertThat(replays).filteredOn(r -> !r).hasSize(1);
        assertThat(amendmentService.listAmendments(docCode, 1)).hasSize(1);
    }
}
