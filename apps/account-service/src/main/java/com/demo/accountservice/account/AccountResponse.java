package com.demo.accountservice.account;

import java.util.List;

/** One account as the frontend and the MCP tools see it: who owns it, which agent it was opened for, and who else may reach it. */
public record AccountResponse(Long id, String owner, Long agentId, String name, long balance, List<PermissionResponse> permissions) {

    public static AccountResponse of(Account a) {
        return new AccountResponse(a.getId(), a.getOwner(), a.getAgentId(), a.getName(), a.getBalance(),
                a.getPermissions().stream().map(PermissionResponse::of).toList());
    }
}
