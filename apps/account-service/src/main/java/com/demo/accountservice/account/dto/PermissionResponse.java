package com.demo.accountservice.account.dto;

import com.demo.accountservice.account.entities.AccountPermission;

public record PermissionResponse(Long agentId, Access access) {

    public static PermissionResponse of(AccountPermission p) {
        return new PermissionResponse(p.getAgentId(), p.getAccess());
    }
}
