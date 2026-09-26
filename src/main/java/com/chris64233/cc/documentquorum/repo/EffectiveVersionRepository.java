package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.EffectiveVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EffectiveVersionRepository extends JpaRepository<EffectiveVersion, Long> {
}
