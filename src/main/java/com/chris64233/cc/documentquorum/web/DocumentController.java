package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.web.ApiRequests.CreateDocumentRequest;
import com.chris64233.cc.documentquorum.web.ApiRequests.CreateVersionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponses.DocumentResponse createDocument(@Valid @RequestBody CreateDocumentRequest request) {
        return ApiResponses.DocumentResponse.from(
                documentService.createDocument(request.code(), request.name()));
    }

    @PostMapping("/{documentId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponses.VersionResponse createVersion(@PathVariable Long documentId,
                                                      @Valid @RequestBody CreateVersionRequest request) {
        List<DocumentService.PolicyRequirementCommand> policy = request.policy().stream()
                .map(item -> new DocumentService.PolicyRequirementCommand(
                        item.role(), item.requiredApprovals(), item.veto()))
                .toList();
        return ApiResponses.VersionResponse.from(
                documentService.createVersion(documentId, request.content(), policy));
    }

    @GetMapping("/{documentId}/versions")
    public List<ApiResponses.VersionResponse> listVersions(@PathVariable Long documentId) {
        return documentService.listVersions(documentId).stream()
                .map(ApiResponses.VersionResponse::from)
                .toList();
    }
}
