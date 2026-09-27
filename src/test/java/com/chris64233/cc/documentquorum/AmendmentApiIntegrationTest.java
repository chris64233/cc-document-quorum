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
class AmendmentApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    String docCode;
    String suffix;
    String qa;
    String admin1;
    String admin2;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        docCode = "DOC-" + suffix;
        qa = "qa-" + suffix;
        admin1 = "adm1-" + suffix;
        admin2 = "adm2-" + suffix;

        mvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docCode\":\"" + docCode + "\",\"title\":\"受控文件\"}"))
                .andExpect(status().isCreated());
        createSigner(qa, "QA");
        createSigner(admin1, "ADMIN");
        createSigner(admin2, "ADMIN");

        mvc.perform(post("/api/documents/" + docCode + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"v1 内容\",\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":2,\"vetoPower\":true}],"
                                + "\"amendment\":{\"role\":\"ADMIN\",\"requiredApprovals\":2}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.activePolicyNo").value(1))
                .andExpect(jsonPath("$.amendRole").value("ADMIN"))
                .andExpect(jsonPath("$.amendRequiredApprovals").value(2));
    }

    private void createSigner(String externalId, String role) throws Exception {
        mvc.perform(post("/api/signers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\":\"" + externalId + "\",\"name\":\"" + externalId
                                + "\",\"roles\":[\"" + role + "\"]}"))
                .andExpect(status().isCreated());
    }

    private String decisionBody(String eventId, String signer, String decision) {
        return "{\"eventId\":\"" + suffix + "-" + eventId + "\",\"signerExternalId\":\"" + signer
                + "\",\"decision\":\"" + decision + "\"}";
    }

    @Test
    void amendmentLifecycleThroughApi() throws Exception {
        // 一份按旧策略未达门槛的同意
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + suffix + "-d1\",\"signerExternalId\":\"" + qa
                                + "\",\"role\":\"QA\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyNo").value(1))
                .andExpect(jsonPath("$.versionStatus").value("PENDING"));

        // 创建修订：QA 门槛降到 1
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amendmentNo\":1,\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":1,\"vetoPower\":true}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amendmentNo").value(1))
                .andExpect(jsonPath("$.basePolicyNo").value(1))
                .andExpect(jsonPath("$.policyNo").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 重复创建同号修订（内容相同）幂等
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amendmentNo\":1,\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":1,\"vetoPower\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 修订未生效前进度仍按 1 号策略
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNo").value(1))
                .andExpect(jsonPath("$.roles[0].met").value(false));

        // 非管理角色投票被拒
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + suffix + "-x1\",\"signerExternalId\":\"" + qa
                                + "\",\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY"));

        // 管理角色投票达到门槛，修订生效，版本随新策略生效
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("a1", admin1, "APPROVE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveNow").value(false))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("a2", admin2, "APPROVE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.effectiveNow").value(true))
                .andExpect(jsonPath("$.effectivePolicyNo").value(2))
                .andExpect(jsonPath("$.carriedOverCount").value(1))
                .andExpect(jsonPath("$.voidedCount").value(0))
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));

        // 查询：策略版本历史、差异、沿用明细、进度、生效依据
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"));
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/policies/diff").param("from", "1").param("to", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].role").value("QA"))
                .andExpect(jsonPath("$.changes[0].changeType").value("CHANGED"))
                .andExpect(jsonPath("$.changes[0].oldRequiredApprovals").value(2))
                .andExpect(jsonPath("$.changes[0].newRequiredApprovals").value(1));
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/amendments/1/carry-over"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].outcome").value("CARRIED_OVER"));
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNo").value(2))
                .andExpect(jsonPath("$.status").value("EFFECTIVE"));
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/effective-basis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("AMENDED_POLICY_QUORUM"))
                .andExpect(jsonPath("$.policyNo").value(2))
                .andExpect(jsonPath("$.amendmentNo").value(1))
                .andExpect(jsonPath("$.effectiveAt").isNotEmpty());

        // 审计列表保留原始决定，且未被作废
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].policyNo").value(1))
                .andExpect(jsonPath("$[0].voidedPolicyNo").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void rejectedVersionAmendmentAttemptReturnsTerminalState() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + suffix + "-d1\",\"signerExternalId\":\"" + qa
                                + "\",\"role\":\"QA\",\"decision\":\"REJECT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionStatus").value("REJECTED"));
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amendmentNo\":1,\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":1,\"vetoPower\":false}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMINAL_STATE"));
    }

    @Test
    void duplicateAmendmentNoWithDifferentPolicyReturnsConflict() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amendmentNo\":5,\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":1,\"vetoPower\":true}]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amendmentNo\":5,\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":2,\"vetoPower\":true}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMENDMENT_CONFLICT"));
    }
}
