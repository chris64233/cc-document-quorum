package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.PolicyVersion;
import com.chris64233.cc.documentquorum.domain.PolicyVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {

    Optional<PolicyVersion> findByVersionIdAndStatus(Long versionId, PolicyVersionStatus status);

    Optional<PolicyVersion> findByVersionIdAndPolicyNo(Long versionId, int policyNo);

    List<PolicyVersion> findByVersionIdOrderByPolicyNoAsc(Long versionId);

    @Query("select max(p.policyNo) from PolicyVersion p where p.version.id = :versionId")
    Optional<Integer> findMaxPolicyNoByVersionId(@Param("versionId") Long versionId);
}
