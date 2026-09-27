package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.service.AmendmentService;
import com.chris64233.cc.documentquorum.service.views.AmendmentView;
import com.chris64233.cc.documentquorum.service.views.AmendmentVoteResultView;
import com.chris64233.cc.documentquorum.service.views.PolicyRequirementView;
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
@RequestMapping("/api/documents/{docCode}/versions/{versionNo}/amendments")
public class AmendmentController {

    private final AmendmentService amendmentService;

    public AmendmentController(AmendmentService amendmentService) {
        this.amendmentService = amendmentService;
    }

    @PostMapping
    public ResponseEntity<AmendmentView> createAmendment(@PathVariable String docCode,
                                                         @PathVariable int versionNo,
                                                         @Valid @RequestBody CreateAmendmentRequest request) {
        List<PolicyRequirementView> policy = request.policy().stream()
                .map(p -> new PolicyRequirementView(p.role(), p.requiredApprovals(), p.vetoPower()))
                .toList();
        AmendmentView view = amendmentService.createAmendment(
                docCode, versionNo, request.amendmentNo(), policy);
        HttpStatus status = view.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(view);
    }

    @GetMapping
    public List<AmendmentView> listAmendments(@PathVariable String docCode,
                                              @PathVariable int versionNo) {
        return amendmentService.listAmendments(docCode, versionNo);
    }

    @GetMapping("/{amendmentNo}")
    public AmendmentView getAmendment(@PathVariable String docCode,
                                      @PathVariable int versionNo,
                                      @PathVariable String amendmentNo) {
        return amendmentService.getAmendment(docCode, versionNo, amendmentNo);
    }

    @PostMapping("/{amendmentNo}/votes")
    public ResponseEntity<AmendmentVoteResultView> vote(@PathVariable String docCode,
                                                        @PathVariable int versionNo,
                                                        @PathVariable String amendmentNo,
                                                        @Valid @RequestBody SubmitAmendmentVoteRequest request) {
        AmendmentVoteResultView result = amendmentService.vote(docCode, versionNo, amendmentNo,
                request.eventId(), request.signerExternalId(), request.decision());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }
}
