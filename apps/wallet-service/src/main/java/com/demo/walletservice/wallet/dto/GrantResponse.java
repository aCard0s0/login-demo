package com.demo.walletservice.wallet.dto;

import com.demo.walletservice.wallet.entities.WalletGrant;

public record GrantResponse(Long agentId, Access access) {

    public static GrantResponse of(WalletGrant p) {
        return new GrantResponse(p.getAgentId(), p.getAccess());
    }
}
