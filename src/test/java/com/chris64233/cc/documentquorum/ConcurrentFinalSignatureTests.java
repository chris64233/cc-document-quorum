package com.chris64233.cc.documentquorum;

import com.chris64233.cc.documentquorum.domain.Decision;
import com.chris64233.cc.documentquorum.domain.DocumentVersion;
import com.chris64233.cc.documentquorum.domain.VersionStatus;
import com.chris64233.cc.documentquorum.repo.DocumentVersionRepository;
import com.chris64233.cc.documentquorum.repo.SignEventRepository;
import com.chris64233.cc.documentquorum.service.ApiException;
import com.chris64233.cc.documentquorum.service.DecisionService;
import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.service.ErrorCode;
import com.chris64233.cc.documentquorum.service.SignerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ConcurrentFinalSignatureTests {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private SignerService signerService;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private DocumentVersionRepository versionRepository;

    @Autowired
    private SignEventRepository signEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicLong eventSequence = new AtomicLong();

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("delete from sign_events");
        jdbcTemplate.update("delete from policy_role_requirements");
        jdbcTemplate.update("delete from document_versions");
        jdbcTemplate.update("delete from signer_roles");
        jdbcTemplate.update("delete from signers");
        jdbcTemplate.update("delete from documents");
    }

    @Test
    void concurrentFinalSignaturesOnTwoVersionsEffectExactlyOnce() throws Exception {
        var document = documentService.createDocument("DOC-C", "并发文件");
        for (int index = 1; index <= 4; index++) {
            signerService.createSigner("s" + index, "Signer" + index, Set.of("QA"));
        }
        DocumentVersion v1 = documentService.createVersion(document.getId(), "v1",
                List.of(new DocumentService.PolicyRequirementCommand("QA", 3, false)));
        DocumentVersion v2 = documentService.createVersion(document.getId(), "v2",
                List.of(new DocumentService.PolicyRequirementCommand("QA", 3, false)));

        // 每个版本先各收两票，最后一票由两个线程并发提交。
        for (int index = 1; index <= 2; index++) {
            decisionService.decide(v1.getId(), nextEvent(), "s" + index, "QA", Decision.APPROVE);
            decisionService.decide(v2.getId(), nextEvent(), "s" + index, "QA", Decision.APPROVE);
        }

        List<Result> results = runConcurrently(List.of(
                () -> decisionService.decide(v1.getId(), nextEvent(), "s3", "QA", Decision.APPROVE),
                () -> decisionService.decide(v2.getId(), nextEvent(), "s4", "QA", Decision.APPROVE)));

        assertThat(results).allSatisfy(result -> assertThat(result.error()).isNull());

        List<DocumentVersion> versions = versionRepository
                .findByDocumentIdOrderByVersionNumberAsc(document.getId());
        long effective = versions.stream()
                .filter(version -> version.getStatus() == VersionStatus.EFFECTIVE).count();
        long superseded = versions.stream()
                .filter(version -> version.getStatus() == VersionStatus.SUPERSEDED).count();
        assertThat(effective).as("同一文件只能有一个生效版本").isEqualTo(1);
        assertThat(superseded).as("另一个版本必须被取代而非重复生效").isEqualTo(1);
        assertThat(signEventRepository.count()).isEqualTo(6);
    }

    @Test
    void concurrentSameSignerFinalSignatureRecordsSingleDecision() throws Exception {
        var document = documentService.createDocument("DOC-D", "同人并发");
        signerService.createSigner("solo", "Solo", Set.of("QA"));
        DocumentVersion version = documentService.createVersion(document.getId(), "v1",
                List.of(new DocumentService.PolicyRequirementCommand("QA", 2, false)));

        List<Result> results = runConcurrently(List.of(
                () -> decisionService.decide(version.getId(), nextEvent(), "solo", "QA", Decision.APPROVE),
                () -> decisionService.decide(version.getId(), nextEvent(), "solo", "QA", Decision.APPROVE),
                () -> decisionService.decide(version.getId(), nextEvent(), "solo", "QA", Decision.APPROVE)));

        long succeeded = results.stream().filter(result -> result.error() == null).count();
        assertThat(succeeded).isEqualTo(1);
        assertThat(results.stream().filter(result -> result.error() != null).count()).isEqualTo(2);
        assertThat(results.stream().map(Result::error).filter(java.util.Objects::nonNull))
                .allSatisfy(error -> assertThat(error)
                        .isInstanceOf(ApiException.class)
                        .extracting(e -> ((ApiException) e).getCode())
                        .isEqualTo(ErrorCode.DUPLICATE_DECISION));
        assertThat(signEventRepository.count()).isEqualTo(1);
        assertThat(versionRepository.findById(version.getId()).orElseThrow().getStatus())
                .isEqualTo(VersionStatus.PENDING);
    }

    @Test
    void concurrentIdenticalEventReplayReturnsOriginalOnce() throws Exception {
        var document = documentService.createDocument("DOC-E", "事件重放");
        signerService.createSigner("solo", "Solo", Set.of("QA"));
        DocumentVersion version = documentService.createVersion(document.getId(), "v1",
                List.of(new DocumentService.PolicyRequirementCommand("QA", 1, false)));

        List<Result> results = runConcurrently(List.of(
                () -> decisionService.decide(version.getId(), "evt-shared", "solo", "QA", Decision.APPROVE),
                () -> decisionService.decide(version.getId(), "evt-shared", "solo", "QA", Decision.APPROVE)));

        assertThat(results).allSatisfy(result -> assertThat(result.error()).isNull());
        assertThat(results.stream().map(Result::outcome)
                .filter(DecisionService.DecisionOutcome::replayed).count()).isEqualTo(1);
        assertThat(signEventRepository.count()).isEqualTo(1);
        assertThat(versionRepository.findById(version.getId()).orElseThrow().getStatus())
                .isEqualTo(VersionStatus.EFFECTIVE);
    }

    private String nextEvent() {
        return "evt-" + eventSequence.incrementAndGet();
    }

    private record Result(DecisionService.DecisionOutcome outcome, Throwable error) {
    }

    private List<Result> runConcurrently(List<Callable<DecisionService.DecisionOutcome>> tasks)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Result>> futures = new ArrayList<>();
            for (Callable<DecisionService.DecisionOutcome> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return new Result(task.call(), null);
                    } catch (Throwable error) {
                        return new Result(null, error);
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
