package com.demo.walletservice.wallet.dto;

import com.demo.walletservice.wallet.entities.Wallet;

import java.util.List;

/** One wallet as the frontend and the MCP tools see it: who owns it, which agent it was opened for, and who else may reach it. */
public record WalletResponse(Long id, String owner, Long agentId, String name, long balance, List<GrantResponse> grants) {

    public static WalletResponse of(Wallet a) {
        return new WalletResponse(a.getId(), a.getOwner(), a.getAgentId(), a.getName(), a.getBalance(),
                a.getGrants().stream().map(GrantResponse::of).toList());
    }
}
