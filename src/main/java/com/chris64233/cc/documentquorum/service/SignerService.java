package com.chris64233.cc.documentquorum.service;

import com.chris64233.cc.documentquorum.domain.Signer;
import com.chris64233.cc.documentquorum.error.BusinessException;
import com.chris64233.cc.documentquorum.error.ErrorCode;
import com.chris64233.cc.documentquorum.repo.SignerRepository;
import com.chris64233.cc.documentquorum.service.views.SignerView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class SignerService {

    private final SignerRepository signerRepo;

    public SignerService(SignerRepository signerRepo) {
        this.signerRepo = signerRepo;
    }

    @Transactional
    public SignerView createSigner(String externalId, String name, Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "签署人至少需要一个角色");
        }
        signerRepo.findByExternalId(externalId).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.DUPLICATE_SIGNER, "签署人已存在: " + externalId);
        });
        Signer saved = signerRepo.save(new Signer(externalId, name, roles));
        return new SignerView(saved.getId(), saved.getExternalId(), saved.getName(), saved.getRoles());
    }

    @Transactional(readOnly = true)
    public SignerView getSigner(String externalId) {
        Signer signer = signerRepo.findByExternalId(externalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNER_NOT_FOUND, "签署人不存在: " + externalId));
        return new SignerView(signer.getId(), signer.getExternalId(), signer.getName(), signer.getRoles());
    }
}
