package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.service.SignerService;
import com.chris64233.cc.documentquorum.service.views.SignerView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/signers")
public class SignerController {

    private final SignerService signerService;

    public SignerController(SignerService signerService) {
        this.signerService = signerService;
    }

    @PostMapping
    public ResponseEntity<SignerView> createSigner(@Valid @RequestBody CreateSignerRequest request) {
        SignerView view = signerService.createSigner(request.externalId(), request.name(), request.roles());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/{externalId}")
    public SignerView getSigner(@PathVariable String externalId) {
        return signerService.getSigner(externalId);
    }
}
