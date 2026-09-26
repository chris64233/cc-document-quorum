package com.chris64233.cc.documentquorum.web;

import com.chris64233.cc.documentquorum.domain.Decision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Set;

public final class ApiRequests {

    private ApiRequests() {
    }

    public record CreateDocumentRequest(
            @NotBlank(message = "文件编码不能为空") String code,
            @NotBlank(message = "文件名称不能为空") String name) {
    }

    public record PolicyRequirementRequest(
            @NotBlank(message = "策略角色不能为空") String role,
            @Min(value = 1, message = "门槛至少为 1") int requiredApprovals,
            boolean veto) {
    }

    public record CreateVersionRequest(
            @NotBlank(message = "版本内容不能为空") String content,
            @NotEmpty(message = "签署策略至少需要一个角色要求")
            List<@Valid PolicyRequirementRequest> policy) {
    }

    public record CreateSignerRequest(
            @NotBlank(message = "签署人标识不能为空") String externalId,
            @NotBlank(message = "签署人姓名不能为空") String displayName,
            @NotEmpty(message = "签署人至少需要一个角色") Set<@NotBlank String> roles) {
    }

    public record DecideRequest(
            @NotBlank(message = "事件号不能为空") String eventNo,
            @NotBlank(message = "签署人标识不能为空") String signerExternalId,
            @NotBlank(message = "签署角色不能为空") String role,
            @NotNull(message = "决定不能为空") Decision decision) {
    }
}
