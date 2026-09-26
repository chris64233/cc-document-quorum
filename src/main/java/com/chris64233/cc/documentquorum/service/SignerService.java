package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.Signer;
import com.chris64233.cc.documentquorum.repo.SignerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class SignerService {

    private final SignerRepository signerRepository;

    public SignerService(SignerRepository signerRepository) {
        this.signerRepository = signerRepository;
    }

    @Transactional
    public Signer createSigner(String externalId, String displayName, Set<String> roles) {
        if (signerRepository.existsByExternalId(externalId)) {
            throw new ApiException(ErrorCode.VALIDATION, "签署人标识已存在: " + externalId);
        }
        return signerRepository.save(new Signer(externalId, displayName, roles));
    }
}
