package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.PolicyVersionMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PolicyVersionMetaRepository extends JpaRepository<PolicyVersionMeta, Long> {

    Optional<PolicyVersionMeta> findByVersionIdAndPolicyVersionNo(Long versionId, int policyVersionNo);

    List<PolicyVersionMeta> findByVersionIdOrderByPolicyVersionNoAsc(Long versionId);
}
