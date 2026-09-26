package com.chris64233.cc.documentquorum.repo;

import com.chris64233.cc.documentquorum.domain.Signer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SignerRepository extends JpaRepository<Signer, Long> {

    Optional<Signer> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);
}
