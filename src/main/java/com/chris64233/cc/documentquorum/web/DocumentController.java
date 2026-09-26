package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.service.SigningService;
import com.chris64233.cc.documentquorum.service.views.DecisionResultView;
import com.chris64233.cc.documentquorum.service.views.DecisionView;
import com.chris64233.cc.documentquorum.service.views.DocumentView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import com.chris64233.cc.documentquorum.service.views.ProgressView;
import com.chris64233.cc.documentquorum.service.views.VersionView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final SigningService signingService;

    public DocumentController(DocumentService documentService, SigningService signingService) {
        this.documentService = documentService;
        this.signingService = signingService;
    }

    @PostMapping
    public ResponseEntity<DocumentView> createDocument(@Valid @RequestBody CreateDocumentRequest request) {
        DocumentView view = documentService.createDocument(request.docCode(), request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/{docCode}")
    public DocumentView getDocument(@PathVariable String docCode) {
        return documentService.getDocument(docCode);
    }

    @PostMapping("/{docCode}/versions")
    public ResponseEntity<VersionView> createVersion(@PathVariable String docCode,
                                                     @Valid @RequestBody CreateVersionRequest request) {
        List<PolicyRequirementView> policy = request.policy().stream()
                .map(p -> new PolicyRequirementView(p.role(), p.requiredApprovals(), p.vetoPower()))
                .toList();
        VersionView view = documentService.createVersion(docCode, request.content(), policy);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/{docCode}/versions")
    public List<VersionView> listVersions(@PathVariable String docCode) {
        return documentService.listVersions(docCode);
    }

    @GetMapping("/{docCode}/versions/{versionNo}")
    public VersionView getVersion(@PathVariable String docCode, @PathVariable int versionNo) {
        return documentService.getVersion(docCode, versionNo);
    }

    @GetMapping("/{docCode}/versions/{versionNo}/progress")
    public ProgressView getProgress(@PathVariable String docCode, @PathVariable int versionNo) {
        return documentService.getProgress(docCode, versionNo);
    }

    @GetMapping("/{docCode}/versions/{versionNo}/decisions")
    public List<DecisionView> listDecisions(@PathVariable String docCode, @PathVariable int versionNo) {
        return documentService.listDecisions(docCode, versionNo);
    }

    @PostMapping("/{docCode}/versions/{versionNo}/decisions")
    public ResponseEntity<DecisionResultView> submitDecision(@PathVariable String docCode,
                                                             @PathVariable int versionNo,
                                                             @Valid @RequestBody SubmitDecisionRequest request) {
        DecisionResultView result = signingService.submit(docCode, versionNo, request.eventId(),
                request.signerExternalId(), request.role(), request.decision());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }
}
