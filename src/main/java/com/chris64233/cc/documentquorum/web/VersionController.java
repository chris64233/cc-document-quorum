package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.service.DecisionService;
import com.chris64233.cc.documentquorum.service.QueryService;
import com.chris64233.cc.documentquorum.web.ApiRequests.DecideRequest;
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
@RequestMapping("/api/versions")
public class VersionController {

    private final DecisionService decisionService;
    private final QueryService queryService;

    public VersionController(DecisionService decisionService, QueryService queryService) {
        this.decisionService = decisionService;
        this.queryService = queryService;
    }

    @PostMapping("/{versionId}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponses.DecisionResponse decide(@PathVariable Long versionId,
                                                @Valid @RequestBody DecideRequest request) {
        DecisionService.DecisionOutcome outcome = decisionService.decide(
                versionId, request.eventNo(), request.signerExternalId(),
                request.role(), request.decision());
        return ApiResponses.DecisionResponse.from(outcome);
    }

    @GetMapping("/{versionId}/progress")
    public QueryService.ProgressView progress(@PathVariable Long versionId) {
        return queryService.progress(versionId);
    }

    @GetMapping("/{versionId}/audit")
    public List<QueryService.AuditEntry> audit(@PathVariable Long versionId) {
        return queryService.audit(versionId);
    }
}
