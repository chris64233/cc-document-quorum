package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.service.AmendmentService;
import com.chris64233.cc.documentquorum.service.DocumentService;
import com.chris64233.cc.documentquorum.service.SigningService;
import com.chris64233.cc.documentquorum.service.views.AmendmentResultView;
import com.chris64233.cc.documentquorum.service.views.AmendmentView;
import com.chris64233.cc.documentquorum.service.views.CarryOverView;
import com.chris64233.cc.documentquorum.service.views.DecisionResultView;
import com.chris64233.cc.documentquorum.service.views.DecisionView;
import com.chris64233.cc.documentquorum.service.views.DocumentView;
import com.chris64233.cc.documentquorum.service.views.EffectiveBasisView;
import com.chris64233.cc.documentquorum.service.views.PolicyDiffView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
import com.chris64233.cc.documentquorum.service.views.PolicyVersionView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final SigningService signingService;
    private final AmendmentService amendmentService;

    public DocumentController(DocumentService documentService, SigningService signingService,
                              AmendmentService amendmentService) {
        this.documentService = documentService;
        this.signingService = signingService;
        this.amendmentService = amendmentService;
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
        List<PolicyRequirementView> policy = toPolicyViews(request.policy());
        String amendRole = request.amendment() != null ? request.amendment().role() : null;
        Integer amendApprovals = request.amendment() != null ? request.amendment().requiredApprovals() : null;
        VersionView view = documentService.createVersion(docCode, request.content(), policy,
                amendRole, amendApprovals);
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

    @GetMapping("/{docCode}/versions/{versionNo}/policies")
    public List<PolicyVersionView> listPolicies(@PathVariable String docCode, @PathVariable int versionNo) {
        return documentService.listPolicyVersions(docCode, versionNo);
    }

    @GetMapping("/{docCode}/versions/{versionNo}/policies/diff")
    public PolicyDiffView diffPolicies(@PathVariable String docCode, @PathVariable int versionNo,
                                       @RequestParam int from, @RequestParam int to) {
        return documentService.diffPolicies(docCode, versionNo, from, to);
    }

    @GetMapping("/{docCode}/versions/{versionNo}/effective-basis")
    public EffectiveBasisView getEffectiveBasis(@PathVariable String docCode, @PathVariable int versionNo) {
        return documentService.getEffectiveBasis(docCode, versionNo);
    }

    @PostMapping("/{docCode}/versions/{versionNo}/amendments")
    public ResponseEntity<AmendmentView> createAmendment(@PathVariable String docCode,
                                                         @PathVariable int versionNo,
                                                         @Valid @RequestBody CreateAmendmentRequest request) {
        AmendmentView view = amendmentService.createAmendment(docCode, versionNo, request.amendmentNo(),
                toPolicyViews(request.policy()));
        HttpStatus status = view.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(view);
    }

    @GetMapping("/{docCode}/versions/{versionNo}/amendments")
    public List<AmendmentView> listAmendments(@PathVariable String docCode, @PathVariable int versionNo) {
        return amendmentService.listAmendments(docCode, versionNo);
    }

    @GetMapping("/{docCode}/versions/{versionNo}/amendments/{amendmentNo}")
    public AmendmentView getAmendment(@PathVariable String docCode, @PathVariable int versionNo,
                                      @PathVariable int amendmentNo) {
        return amendmentService.getAmendment(docCode, versionNo, amendmentNo);
    }

    @PostMapping("/{docCode}/versions/{versionNo}/amendments/{amendmentNo}/decisions")
    public ResponseEntity<AmendmentResultView> voteAmendment(
            @PathVariable String docCode, @PathVariable int versionNo, @PathVariable int amendmentNo,
            @Valid @RequestBody SubmitAmendmentDecisionRequest request) {
        AmendmentResultView result = amendmentService.vote(docCode, versionNo, amendmentNo,
                request.eventId(), request.signerExternalId(), request.decision());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/{docCode}/versions/{versionNo}/amendments/{amendmentNo}/carry-over")
    public List<CarryOverView> listCarryOver(@PathVariable String docCode, @PathVariable int versionNo,
                                             @PathVariable int amendmentNo) {
        return amendmentService.listCarryOver(docCode, versionNo, amendmentNo);
    }

    private List<PolicyRequirementView> toPolicyViews(List<PolicyRequirementRequest> policy) {
        return policy.stream()
                .map(p -> new PolicyRequirementView(p.role(), p.requiredApprovals(), p.vetoPower()))
                .toList();
    }
}
