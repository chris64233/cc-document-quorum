package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.PolicyAmendment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PolicyAmendmentRepository extends JpaRepository<PolicyAmendment, Long> {

    Optional<PolicyAmendment> findByVersionIdAndAmendmentNo(Long versionId, String amendmentNo);

    List<PolicyAmendment> findByVersionIdOrderByIdAsc(Long versionId);
}
