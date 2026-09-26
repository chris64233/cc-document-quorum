package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.SignDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SignDecisionRepository extends JpaRepository<SignDecision, Long> {

    Optional<SignDecision> findByEventId(String eventId);

    boolean existsByVersionIdAndSignerId(Long versionId, Long signerId);

    List<SignDecision> findByVersionIdOrderByIdAsc(Long versionId);
}
