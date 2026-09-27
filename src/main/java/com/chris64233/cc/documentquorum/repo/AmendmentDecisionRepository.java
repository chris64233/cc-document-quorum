package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.AmendmentDecision;
import com.chris64233.cc.documentquorum.domain.DecisionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AmendmentDecisionRepository extends JpaRepository<AmendmentDecision, Long> {

    Optional<AmendmentDecision> findByEventId(String eventId);

    boolean existsByAmendmentIdAndSignerId(Long amendmentId, Long signerId);

    long countByAmendmentIdAndDecision(Long amendmentId, DecisionType decision);

    List<AmendmentDecision> findByAmendmentIdOrderByIdAsc(Long amendmentId);
}
