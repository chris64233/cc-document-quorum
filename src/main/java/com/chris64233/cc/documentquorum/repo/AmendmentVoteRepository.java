package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.AmendmentVote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AmendmentVoteRepository extends JpaRepository<AmendmentVote, Long> {

    Optional<AmendmentVote> findByEventId(String eventId);

    boolean existsByAmendmentIdAndSignerId(Long amendmentId, Long signerId);

    List<AmendmentVote> findByAmendmentIdOrderByIdAsc(Long amendmentId);
}
