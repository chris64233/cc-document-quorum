package com.chris64233.cc.documentquorum;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DecisionFlowTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long documentId;
    private long versionId;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("delete from sign_events");
        jdbcTemplate.update("delete from policy_role_requirements");
        jdbcTemplate.update("delete from document_versions");
        jdbcTemplate.update("delete from signer_roles");
        jdbcTemplate.update("delete from signers");
        jdbcTemplate.update("delete from documents");

        documentId = createDocument("DOC-1", "受控文件一");
        createSigner("alice", "Alice", "QA", "OPS");
        createSigner("bob", "Bob", "QA");
        createSigner("carol", "Carol", "OPS");
        createSigner("dave", "Dave", "LEGAL");
        versionId = createVersion(documentId, "版本一内容",
                """
                [{"role":"QA","requiredApprovals":2,"veto":false},
                 {"role":"LEGAL","requiredApprovals":1,"veto":true}]
                """);
    }

    @Test
    void versionBecomesEffectiveWhenAllThresholdsMet() throws Exception {
        decide(versionId, "e-1", "alice", "QA", "APPROVE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionStatus").value("PENDING"));
        decide(versionId, "e-2", "bob", "QA", "APPROVE")
                .andExpect(jsonPath("$.versionStatus").value("PENDING"));
        decide(versionId, "e-3", "dave", "LEGAL", "APPROVE")
                .andExpect(jsonPath("$.versionStatus").value("EFFECTIVE"));

        mockMvc.perform(get("/api/versions/{id}/progress", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.roles[0].role").value("QA"))
                .andExpect(jsonPath("$.roles[0].approvedCount").value(2))
                .andExpect(jsonPath("$.roles[0].thresholdMet").value(true))
                .andExpect(jsonPath("$.roles[1].role").value("LEGAL"))
                .andExpect(jsonPath("$.roles[1].thresholdMet").value(true));

        mockMvc.perform(get("/api/versions/{id}/audit", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventNo").value("e-1"))
                .andExpect(jsonPath("$[2].signerExternalId").value("dave"));
    }

    @Test
    void vetoRejectMovesVersionToRejectedTerminalState() throws Exception {
        decide(versionId, "e-1", "alice", "QA", "APPROVE").andExpect(status().isCreated());
        decide(versionId, "e-2", "dave", "LEGAL", "REJECT")
                .andExpect(jsonPath("$.versionStatus").value("REJECTED"));

        decide(versionId, "e-3", "bob", "QA", "APPROVE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMINAL_STATE"));
    }

    @Test
    void nonVetoRejectDoesNotTerminateVersion() throws Exception {
        decide(versionId, "e-1", "alice", "QA", "REJECT")
                .andExpect(jsonPath("$.versionStatus").value("PENDING"));
        decide(versionId, "e-2", "bob", "QA", "APPROVE").andExpect(status().isCreated());
        decide(versionId, "e-3", "carol", "OPS", "APPROVE")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY"));
    }

    @Test
    void eligibilityErrorsAreDistinguished() throws Exception {
        decide(versionId, "e-1", "carol", "LEGAL", "APPROVE")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY"));
        decide(versionId, "e-2", "alice", "HR", "APPROVE")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY"));
        decide(versionId, "e-3", "nobody", "QA", "APPROVE")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void duplicateDecisionBySameSignerIsRejected() throws Exception {
        decide(versionId, "e-1", "alice", "QA", "APPROVE").andExpect(status().isCreated());
        decide(versionId, "e-2", "alice", "QA", "REJECT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DECISION"));
    }

    @Test
    void sameEventReplayReturnsOriginalAndDifferentContentConflicts() throws Exception {
        decide(versionId, "e-1", "alice", "QA", "APPROVE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));

        decide(versionId, "e-1", "alice", "QA", "APPROVE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.signerExternalId").value("alice"));

        decide(versionId, "e-1", "alice", "QA", "REJECT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CONFLICT"));

        decide(versionId, "e-1", "bob", "QA", "APPROVE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CONFLICT"));

        mockMvc.perform(get("/api/versions/{id}/audit", versionId))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void replayAfterTerminalStateStillReturnsOriginalResult() throws Exception {
        decide(versionId, "e-1", "dave", "LEGAL", "REJECT")
                .andExpect(jsonPath("$.versionStatus").value("REJECTED"));
        decide(versionId, "e-1", "dave", "LEGAL", "REJECT")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.versionStatus").value("REJECTED"));
    }

    @Test
    void versionNumbersIncreaseStrictlyAndContentIsReturned() throws Exception {
        long v2 = createVersion(documentId, "版本二内容",
                """
                [{"role":"QA","requiredApprovals":1,"veto":false}]
                """);
        mockMvc.perform(get("/api/documents/{id}/versions", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionNumber").value(1))
                .andExpect(jsonPath("$[0].content").value("版本一内容"))
                .andExpect(jsonPath("$[1].versionNumber").value(2))
                .andExpect(jsonPath("$[1].policy[0].role").value("QA"));
        assertThat(v2).isNotEqualTo(versionId);
    }

    @Test
    void newEffectiveVersionSupersedesPreviousOne() throws Exception {
        decide(versionId, "e-1", "alice", "QA", "APPROVE");
        decide(versionId, "e-2", "bob", "QA", "APPROVE");
        decide(versionId, "e-3", "dave", "LEGAL", "APPROVE")
                .andExpect(jsonPath("$.versionStatus").value("EFFECTIVE"));

        long v2 = createVersion(documentId, "版本二内容",
                """
                [{"role":"QA","requiredApprovals":1,"veto":false}]
                """);
        decide(v2, "e-4", "alice", "QA", "APPROVE")
                .andExpect(jsonPath("$.versionStatus").value("EFFECTIVE"));

        mockMvc.perform(get("/api/documents/{id}/versions", documentId))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].status").value("EFFECTIVE"));

        decide(versionId, "e-5", "carol", "OPS", "APPROVE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMINAL_STATE"));
    }

    @Test
    void validationErrorsAreReported() throws Exception {
        mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        mockMvc.perform(post("/api/documents/{id}/versions", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"x\",\"policy\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    private long createDocument(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void createSigner(String externalId, String name, String... roles) throws Exception {
        String roleJson = java.util.Arrays.stream(roles)
                .map(role -> "\"" + role + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        mockMvc.perform(post("/api/signers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"" + externalId + "\",\"displayName\":\""
                                + name + "\",\"roles\":[" + roleJson + "]}"))
                .andExpect(status().isCreated());
    }

    private long createVersion(long docId, String content, String policyJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/documents/{id}/versions", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\",\"policy\":" + policyJson + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private org.springframework.test.web.servlet.ResultActions decide(
            long verId, String eventNo, String signer, String role, String decision) throws Exception {
        return mockMvc.perform(post("/api/versions/{id}/decisions", verId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventNo\":\"" + eventNo + "\",\"signerExternalId\":\"" + signer
                        + "\",\"role\":\"" + role + "\",\"decision\":\"" + decision + "\"}"));
    }
}
