package com.chris64233.cc.documentquorum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    String docCode;
    String suffix;
    String qa;
    String rev1;
    String rev2;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        docCode = "DOC-" + suffix;
        qa = "qa-" + suffix;
        rev1 = "rev1-" + suffix;
        rev2 = "rev2-" + suffix;

        mvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docCode\":\"" + docCode + "\",\"title\":\"受控文件\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.docCode").value(docCode));

        createSigner(qa, "QA");
        createSigner(rev1, "REVIEWER");
        createSigner(rev2, "REVIEWER");

        mvc.perform(post("/api/documents/" + docCode + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"v1 内容\",\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":1,\"vetoPower\":true},"
                                + "{\"role\":\"REVIEWER\",\"requiredApprovals\":2,\"vetoPower\":false}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private void createSigner(String externalId, String role) throws Exception {
        mvc.perform(post("/api/signers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"" + externalId + "\",\"name\":\"" + externalId
                                + "\",\"roles\":[\"" + role + "\"]}"))
                .andExpect(status().isCreated());
    }

    private String decisionBody(String eventId, String signer, String role, String decision) {
        return "{\"eventId\":\"" + suffix + "-" + eventId + "\",\"signerExternalId\":\"" + signer
                + "\",\"role\":\"" + role + "\",\"decision\":\"" + decision + "\"}";
    }

    @Test
    void fullQuorumFlowActivatesVersionAndExposesProgressAndAudit() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e1", qa, "QA", "APPROVE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionStatus").value("PENDING"));
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e2", rev1, "REVIEWER", "APPROVE")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e3", rev2, "REVIEWER", "APPROVE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionStatus").value("EFFECTIVE"));

        mvc.perform(get("/api/documents/" + docCode + "/versions/1/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.roles[0].met").value(true))
                .andExpect(jsonPath("$.roles[1].met").value(true));

        mvc.perform(get("/api/documents/" + docCode + "/versions/1/decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventId").value(suffix + "-e1"));

        mvc.perform(get("/api/documents/" + docCode + "/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("EFFECTIVE"));
    }

    @Test
    void replayReturnsOriginalResultWithOk() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("evt-1", qa, "QA", "APPROVE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("evt-1", qa, "QA", "APPROVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void sameEventDifferentContentReturnsEventConflict() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("evt-1", qa, "QA", "APPROVE")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("evt-1", rev1, "REVIEWER", "APPROVE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CONFLICT"));
    }

    @Test
    void duplicateDecisionBySameSignerRejected() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e1", qa, "QA", "APPROVE")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e2", qa, "QA", "APPROVE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DECISION"));
    }

    @Test
    void signerWithoutRoleGetsEligibilityError() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e1", rev1, "QA", "APPROVE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY"));
    }

    @Test
    void vetoRejectTerminatesVersionAndBlocksFurtherDecisions() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e1", qa, "QA", "REJECT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionStatus").value("REJECTED"));
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e2", rev1, "REVIEWER", "APPROVE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMINAL_STATE"));
    }

    @Test
    void unknownResourcesReturnNotFound() throws Exception {
        mvc.perform(get("/api/documents/NOPE/versions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOCUMENT_NOT_FOUND"));
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e1", "ghost", "QA", "APPROVE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SIGNER_NOT_FOUND"));
    }

    @Test
    void invalidRequestBodyReturnsValidationError() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\",\"policy\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }
}
