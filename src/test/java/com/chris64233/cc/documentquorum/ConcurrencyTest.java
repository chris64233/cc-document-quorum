package com.chris64233.cc.documentquorum;

import com.chris64233.cc.documentquorum.domain.DecisionType;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.EffectiveVersionRepository;
import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.service.SignerService;
import com.chris64233.cc.documentquorum.service.SigningService;
import com.chris64233.cc.documentquorum.service.views.DecisionResultView;
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
class ConcurrencyTest {

    @Autowired
    DocumentService documentService;
    @Autowired
    SigningService signingService;
    @Autowired
    SignerService signerService;
    @Autowired
    EffectiveVersionRepository effectiveRepo;

    private String newDoc() {
        String docCode = "DOC-" + UUID.randomUUID();
        documentService.createDocument(docCode, "并发测试文件");
        return docCode;
    }

    private String newSigner(String prefix, String... roles) {
        String externalId = prefix + "-" + UUID.randomUUID();
        signerService.createSigner(externalId, externalId, Set.of(roles));
        return externalId;
    }

    @Test
    void concurrentFinalApprovalsOnTwoVersionsActivateExactlyOnce() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String signerA = newSigner("a", "APPROVER");
        String signerB = newSigner("b", "APPROVER");
        documentService.createVersion(docCode, "v1", List.of(new PolicyRequirementView("APPROVER", 1, false)));
        documentService.createVersion(docCode, "v2", List.of(new PolicyRequirementView("APPROVER", 1, false)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        Future<?> f1 = pool.submit(() -> awaitGateAndRun(gate, errors,
                () -> signingService.submit(docCode, 1, run + "-v1", signerA, "APPROVER", DecisionType.APPROVE)));
        Future<?> f2 = pool.submit(() -> awaitGateAndRun(gate, errors,
                () -> signingService.submit(docCode, 2, run + "-v2", signerB, "APPROVER", DecisionType.APPROVE)));
        gate.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(errors).isEmpty();
        List<VersionView> versions = documentService.listVersions(docCode);
        assertThat(versions).filteredOn(v -> v.status().equals("EFFECTIVE")).hasSize(1);
        assertThat(versions).filteredOn(v -> v.status().equals("SUPERSEDED")).hasSize(1);

        Long documentId = documentService.getDocument(docCode).id();
        var effective = effectiveRepo.findById(documentId).orElseThrow();
        VersionView effectiveView = versions.stream()
                .filter(v -> v.status().equals("EFFECTIVE"))
                .findFirst()
                .orElseThrow();
        assertThat(effective.getVersion().getId()).isEqualTo(effectiveView.id());
    }

    @Test
    void concurrentDecisionsBySameSignerRecordedOnlyOnce() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String signer = newSigner("solo", "APPROVER");
        documentService.createVersion(docCode, "v1", List.of(new PolicyRequirementView("APPROVER", 2, false)));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ErrorCode> errorCodes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<DecisionResultView> successes = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String eventId = run + "-" + i;
            futures.add(pool.submit(() -> awaitGateAndRun(gate, errors, () -> {
                try {
                    successes.add(signingService.submit(docCode, 1, eventId, signer, "APPROVER", DecisionType.APPROVE));
                } catch (BusinessException ex) {
                    errorCodes.add(ex.getCode());
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
        assertThat(successes).hasSize(1);
        assertThat(errorCodes).hasSize(threads - 1)
                .allMatch(code -> code == ErrorCode.DUPLICATE_DECISION);
        assertThat(documentService.listDecisions(docCode, 1)).hasSize(1);
    }

    @Test
    void concurrentSameEventSameContentIsIdempotent() throws Exception {
        String docCode = newDoc();
        String run = UUID.randomUUID().toString().substring(0, 8);
        String signer = newSigner("replay", "APPROVER");
        documentService.createVersion(docCode, "v1", List.of(new PolicyRequirementView("APPROVER", 2, false)));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<DecisionResultView> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> awaitGateAndRun(gate, errors, () -> {
                results.add(signingService.submit(docCode, 1, run + "-shared", signer, "APPROVER", DecisionType.APPROVE));
                return null;
            })));
        }
        gate.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threads);
        assertThat(results).filteredOn(DecisionResultView::replayed).hasSize(threads - 1);
        assertThat(documentService.listDecisions(docCode, 1)).hasSize(1);
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
}
