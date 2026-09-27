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
    String qa1;
    String rev1;
    String rev2;
    String admin;

    @BeforeEach
    void setUp() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        docCode = "DOC-" + suffix;
        qa1 = "qa1-" + suffix;
        rev1 = "rev1-" + suffix;
        rev2 = "rev2-" + suffix;
        admin = "admin-" + suffix;

        mvc.perform(post("/api/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"docCode\":\"" + docCode + "\",\"title\":\"受控文件\"}"))
                .andExpect(status().isCreated());

        createSigner(qa1, "QA");
        createSigner(rev1, "REVIEWER");
        createSigner(rev2, "REVIEWER");
        createSigner(admin, "ADMIN");

        mvc.perform(post("/api/documents/" + docCode + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"v1 内容\",\"amendmentRole\":\"ADMIN\","
                                + "\"amendmentThreshold\":1,\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":1,\"vetoPower\":false},"
                                + "{\"role\":\"REVIEWER\",\"requiredApprovals\":2,\"vetoPower\":false}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.currentPolicyVersionNo").value(1));
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

    private String amendmentBody(String amendmentNo, String policyJson) {
        return "{\"amendmentNo\":\"" + amendmentNo + "\",\"policy\":" + policyJson + "}";
    }

    private String voteBody(String eventId, String signer, String decision) {
        return "{\"eventId\":\"" + suffix + "-" + eventId + "\",\"signerExternalId\":\"" + signer
                + "\",\"decision\":\"" + decision + "\"}";
    }

    private void decide(String eventId, String signer, String role, String decision) throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody(eventId, signer, role, decision)))
                .andExpect(status().isCreated());
    }

    @Test
    void fullAmendmentFlowViaApi() throws Exception {
        decide("e1", qa1, "QA", "APPROVE");
        decide("e2", rev1, "REVIEWER", "APPROVE");

        // 创建修订：移除 QA，REVIEWER 门槛降为 1
        String policy = "[{\"role\":\"REVIEWER\",\"requiredApprovals\":1,\"vetoPower\":false}]";
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentBody("A1", policy)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.basePolicyVersionNo").value(1))
                .andExpect(jsonPath("$.amendmentRole").value("ADMIN"))
                .andExpect(jsonPath("$.amendmentThreshold").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        // 相同修订号相同内容重放
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentBody("A1", policy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 相同修订号不同内容冲突
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentBody("A1",
                                "[{\"role\":\"REVIEWER\",\"requiredApprovals\":2,\"vetoPower\":false}]")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CONFLICT"));

        // 管理角色投票达到门槛，修订生效；沿用的 REVIEWER 同意使版本直接生效
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments/A1/votes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voteBody("v1", admin, "APPROVE")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amendmentStatus").value("ENACTED"))
                .andExpect(jsonPath("$.versionStatus").value("EFFECTIVE"));

        // 投票事件重放
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments/A1/votes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voteBody("v1", admin, "APPROVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.amendmentStatus").value("ENACTED"));

        // 策略版本列表
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[1].active").value(true))
                .andExpect(jsonPath("$[1].sourceAmendmentNo").value("A1"));

        // 策略差异
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/policy/diff?from=1&to=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(2));

        // 决定沿用明细：QA 同意不再计数，REVIEWER 同意沿用
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/decisions/carry-over"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPolicyVersionNo").value(2))
                .andExpect(jsonPath("$.decisions.length()").value(2))
                .andExpect(jsonPath("$.decisions[0].counted").value(false))
                .andExpect(jsonPath("$.decisions[0].reason").value("ROLE_NOT_IN_CURRENT_POLICY"))
                .andExpect(jsonPath("$.decisions[1].counted").value(true))
                .andExpect(jsonPath("$.decisions[1].reason").value("CARRIED_OVER"));

        // 进度按新策略计算
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.policyVersionNo").value(2))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0].met").value(true));

        // 生效依据
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/effectiveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectivePolicyVersionNo").value(2))
                .andExpect(jsonPath("$.countedDecisions.length()").value(1));

        // 修订详情
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/amendments/A1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENACTED"))
                .andExpect(jsonPath("$.enactedPolicyVersionNo").value(2))
                .andExpect(jsonPath("$.votes.length()").value(1));
    }

    @Test
    void amendmentOnRejectedVersionRejected() throws Exception {
        decide("e1", qa1, "QA", "APPROVE");
        // QA 无否决权，改用带否决权的策略场景在服务端测试中覆盖；此处验证终态版本不能修订
        mvc.perform(post("/api/documents/" + docCode + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"v2\",\"amendmentRole\":\"ADMIN\","
                                + "\"amendmentThreshold\":1,\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":1,\"vetoPower\":true}]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/documents/" + docCode + "/versions/2/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("e2", qa1, "QA", "REJECT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionStatus").value("REJECTED"));

        mvc.perform(post("/api/documents/" + docCode + "/versions/2/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentBody("A1",
                                "[{\"role\":\"QA\",\"requiredApprovals\":1,\"vetoPower\":false}]")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMINAL_STATE"));
    }

    @Test
    void amendmentWithoutAdminRoleInPolicyFailsValidation() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"v2\",\"policy\":["
                                + "{\"role\":\"QA\",\"requiredApprovals\":1,\"vetoPower\":false}]}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/documents/" + docCode + "/versions/2/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentBody("A1",
                                "[{\"role\":\"QA\",\"requiredApprovals\":2,\"vetoPower\":false}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void voteByNonAdminRoleIsForbidden() throws Exception {
        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(amendmentBody("A1",
                                "[{\"role\":\"QA\",\"requiredApprovals\":2,\"vetoPower\":false}]")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/documents/" + docCode + "/versions/1/amendments/A1/votes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(voteBody("v1", qa1, "APPROVE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ELIGIBILITY"));
    }

    @Test
    void unknownAmendmentAndEffectivenessReturnNotFound() throws Exception {
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/amendments/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AMENDMENT_NOT_FOUND"));
        mvc.perform(get("/api/documents/" + docCode + "/versions/1/effectiveness"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_EFFECTIVE"));
    }
}
