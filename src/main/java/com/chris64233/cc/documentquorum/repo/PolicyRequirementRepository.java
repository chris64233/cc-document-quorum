package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.PolicyRequirement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PolicyRequirementRepository extends JpaRepository<PolicyRequirement, Long> {

    List<PolicyRequirement> findByVersionId(Long versionId);

    Optional<PolicyRequirement> findByVersionIdAndRole(Long versionId, String role);
}
