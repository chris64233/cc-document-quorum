package com.chris64233.cc.documentquorum;

import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.domain.PolicyVersionStatus;
import com.chris64233.cc.documentquorum.repo.EffectiveVersionRepository;
import com.chris64233.cc.documentquorum.repo.PolicyVersionRepository;
import com.chris64233.cc.documentquorum.service.AmendmentService;
import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.service.SignerService;
import com.chris64233.cc.documentquorum.service.SigningService;
import com.chris64233.cc.documentquorum.service.views.AmendmentResultView;
import com.chris64233.cc.documentquorum.service.views.AmendmentView;
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
    @Autowired
    EffectiveVersionRepository effectiveRepo;
    @Autowired
    PolicyVersionRepository policyVersionRepo;

    private String newDoc() {
        String docCode = "DOC-" + UUID.randomUUID();
        documentService.createDocument(docCode, "并发修订测试文件");
        return docCode;
    }

    private String newSigner(String prefix, String... roles) {
        String externalId = prefix + "-" + UUID.randomUUID();
        signerService.createSigner(externalId, externalId, Set.of(roles));
        return externalId;
    }

    private PolicyRequirementView req(String role, int n) {
        return new PolicyRequirementView(role, n, false);
    }

    /**
     * 修订最后一票与普通签署最后一票并发：
     * 版本行悲观锁串行化两者，恰好一个事务先让版本生效，另一个收到 TERMINAL_STATE；
     * 版本不会同时按两套策略生效。
     */
    @Test
    void concurrentFinalAmendmentVoteAndFinalSignatureSerializeOnVersionLock() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String approver1 = newSigner("ap1", "APPROVER");
        String approver2 = newSigner("ap2", "APPROVER");
        String admin = newSigner("admin", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(req("APPROVER", 2)), "ADMIN", 1);

        signingService.submit(docCode, 1, run + "-d1", approver1, "APPROVER", DecisionType.APPROVE);
        amendmentService.createAmendment(docCode, 1, 1, List.of(req("APPROVER", 1)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> terminalConflicts = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<AmendmentResultView> amendmentResults = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> effectiveWinners = new ConcurrentLinkedQueue<>();

        Future<?> f1 = pool.submit(() -> {
            try {
                awaitGate(gate);
                signingService.submit(docCode, 1, run + "-d2", approver2, "APPROVER", DecisionType.APPROVE);
                effectiveWinners.add("SIGNATURE");
            } catch (BusinessException ex) {
                if (ex.getCode() == ErrorCode.TERMINAL_STATE) {
                    terminalConflicts.add(ex.getCode());
                } else {
                    unexpected.add(ex);
                }
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });
        Future<?> f2 = pool.submit(() -> {
            try {
                awaitGate(gate);
                AmendmentResultView result = amendmentService.vote(docCode, 1, 1, run + "-a1", admin,
                        DecisionType.APPROVE);
                amendmentResults.add(result);
                if (result.effectiveNow()) {
                    effectiveWinners.add("AMENDMENT");
                }
            } catch (BusinessException ex) {
                if (ex.getCode() == ErrorCode.TERMINAL_STATE) {
                    terminalConflicts.add(ex.getCode());
                } else {
                    unexpected.add(ex);
                }
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });
        gate.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(terminalConflicts).hasSize(1);
        assertThat(effectiveWinners).hasSize(1);
        assertThat(effectiveWinners.peek()).isIn("SIGNATURE", "AMENDMENT");

        VersionView version = documentService.getVersion(docCode, 1);
        assertThat(version.status()).isEqualTo("EFFECTIVE");
        assertThat(version.activePolicyNo()).isEqualTo(effectiveWinners.contains("AMENDMENT") ? 2 : 1);

        Long versionId = version.id();
        assertThat(policyVersionRepo.findByVersionIdAndStatus(versionId, PolicyVersionStatus.ACTIVE))
                .hasValueSatisfying(pv -> assertThat(pv.getPolicyNo()).isEqualTo(version.activePolicyNo()));
        assertThat(policyVersionRepo.findByVersionIdOrderByPolicyNoAsc(versionId)).hasSize(2);
        assertThat(effectiveRepo.findById(documentService.getDocument(docCode).id()).orElseThrow()
                .getVersion().getId()).isEqualTo(versionId);
    }

    /**
     * 修订生效与新文件版本创建并发：新文件版本取号与修订生效互不影响，
     * 修订不会错误取代较新的文件版本，新文件版本号严格递增。
     */
    @Test
    void concurrentAmendmentEnactAndVersionCreationKeepStatesConsistent() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String approver = newSigner("ap", "APPROVER");
        String admin = newSigner("adm", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(req("APPROVER", 2)), "ADMIN", 1);
        signingService.submit(docCode, 1, run + "-d1", approver, "APPROVER", DecisionType.APPROVE);
        amendmentService.createAmendment(docCode, 1, 1, List.of(req("APPROVER", 1)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        Future<?> f1 = pool.submit(() -> {
            try {
                awaitGate(gate);
                amendmentService.vote(docCode, 1, 1, run + "-a1", admin, DecisionType.APPROVE);
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        Future<?> f2 = pool.submit(() -> {
            try {
                awaitGate(gate);
                documentService.createVersion(docCode, "v2", List.of(req("APPROVER", 1)), "ADMIN", 1);
            } catch (Throwable t) {
                errors.add(t);
            }
        });
        gate.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(errors).isEmpty();
        List<VersionView> versions = documentService.listVersions(docCode);
        assertThat(versions).extracting(VersionView::versionNo).containsExactly(1, 2);
        assertThat(versions.get(0).status()).isEqualTo("EFFECTIVE");
        assertThat(versions.get(0).activePolicyNo()).isEqualTo(2);
        assertThat(versions.get(1).status()).isEqualTo("PENDING");
        assertThat(versions.get(1).activePolicyNo()).isEqualTo(1);

        // 生效指向不能被较新或较旧的版本错误取代
        assertThat(effectiveRepo.findById(documentService.getDocument(docCode).id()).orElseThrow()
                .getVersion().getId()).isEqualTo(versions.get(0).id());
    }

    /** 并发创建相同修订号相同内容：恰好一次创建，其余幂等返回，不产生重复修订 */
    @Test
    void concurrentSameAmendmentNoCreationIsIdempotent() throws Exception {
        String docCode = newDoc();
        documentService.createVersion(docCode, "v1", List.of(req("APPROVER", 1)), "ADMIN", 1);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<AmendmentView> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    awaitGate(gate);
                    results.add(amendmentService.createAmendment(docCode, 1, 3, List.of(req("APPROVER", 1))));
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        gate.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threads);
        assertThat(results).filteredOn(AmendmentView::replayed).hasSize(threads - 1);
        assertThat(amendmentService.listAmendments(docCode, 1)).hasSize(1);
    }

    /** 同一管理角色并发重复投票：恰好一次成功 */
    @Test
    void concurrentDuplicateAdminVotesRecordedOnlyOnce() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String admin = newSigner("adm", "ADMIN");
        documentService.createVersion(docCode, "v1", List.of(req("APPROVER", 1)), "ADMIN", 2);
        amendmentService.createAmendment(docCode, 1, 1, List.of(req("APPROVER", 1)));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<ErrorCode> codes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String eventId = run + "-a" + i;
            futures.add(pool.submit(() -> {
                try {
                    awaitGate(gate);
                    amendmentService.vote(docCode, 1, 1, eventId, admin, DecisionType.APPROVE);
                } catch (BusinessException ex) {
                    codes.add(ex.getCode());
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        gate.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(errors).isEmpty();
        assertThat(codes).hasSize(threads - 1)
                .allMatch(code -> code == ErrorCode.DUPLICATE_DECISION);
        AmendmentView amendment = amendmentService.getAmendment(docCode, 1, 1);
        assertThat(amendment.approvalCount()).isEqualTo(1);
        assertThat(amendment.status()).isEqualTo("PENDING");
    }

    private void awaitGate(CountDownLatch gate) throws InterruptedException {
        gate.await(10, TimeUnit.SECONDS);
    }
}
