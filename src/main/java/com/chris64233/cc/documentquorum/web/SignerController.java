package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.service.SignerService;
import com.chris64233.cc.documentquorum.web.ApiRequests.CreateSignerRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/signers")
public class SignerController {

    private final SignerService signerService;

    public SignerController(SignerService signerService) {
        this.signerService = signerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponses.SignerResponse createSigner(@Valid @RequestBody CreateSignerRequest request) {
        return ApiResponses.SignerResponse.from(signerService.createSigner(
                request.externalId(), request.displayName(), request.roles()));
    }
}
