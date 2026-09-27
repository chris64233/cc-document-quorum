package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.AmendmentRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AmendmentRequirementRepository extends JpaRepository<AmendmentRequirement, Long> {

    List<AmendmentRequirement> findByAmendmentId(Long amendmentId);
}
