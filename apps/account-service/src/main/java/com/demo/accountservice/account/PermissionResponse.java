package com.demo.accountservice.account;

public record PermissionResponse(Long agentId, Access access) {

    public static PermissionResponse of(AccountPermission p) {
        return new PermissionResponse(p.getAgentId(), p.getAccess());
    }
}
