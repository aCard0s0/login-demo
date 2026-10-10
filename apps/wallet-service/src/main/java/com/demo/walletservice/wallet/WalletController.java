package com.demo.walletservice.wallet;

import com.demo.walletservice.wallet.dto.*;
import com.demo.auth.client.Caller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Every endpoint here is private: who is asking comes from the caller's token, never from the request body,
 * and what that answer is allowed to reach is {@link WalletService}'s decision rather than this class's.
 *
 * <p>Only a user's own tokens are accepted: {@link com.demo.auth.client.CallerResolver} answers an agent token
 * with 403 before any method here runs. An agent token reaches the wallets over {@code /mcp} through
 * agent-service, where its owner's READ/WRITE setting and activity log apply; let in here, it would skip both.
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping
    public List<WalletResponse> list(Caller caller) {
        return wallets.list(caller).stream().map(WalletResponse::of).toList();
    }

    @PostMapping
    public WalletResponse create(Caller caller, @RequestBody NewWallet in) {
        return WalletResponse.of(wallets.create(caller, in));
    }

    @GetMapping("/{id}")
    public WalletResponse one(Caller caller, @PathVariable Long id) {
        return WalletResponse.of(wallets.get(caller, id));
    }

    @PostMapping("/{id}/deposit")
    public TransferResponse deposit(Caller caller, @PathVariable Long id, @RequestBody Deposit in) {
        return TransferResponse.of(wallets.deposit(caller, id, in.amount()));
    }

    /** Moves money out of the wallet in the path into {@code to}. */
    @PostMapping("/{id}/transfers")
    public TransferResponse transfer(Caller caller, @PathVariable Long id, @RequestBody NewTransfer in) {
        return TransferResponse.of(wallets.transfer(caller, id, in.to(), in.amount()));
    }

    @GetMapping("/{id}/transfers")
    public List<TransferResponse> transfers(Caller caller, @PathVariable Long id) {
        return wallets.transfers(caller, id).stream().map(TransferResponse::of).toList();
    }

    @PutMapping("/{id}/grants/{agentId}")
    public WalletResponse setGrant(Caller caller, @PathVariable Long id, @PathVariable Long agentId,
                                         @RequestBody SetGrant in) {
        return WalletResponse.of(wallets.setGrant(caller, id, agentId, in.access()));
    }

    @DeleteMapping("/{id}/grants/{agentId}")
    public WalletResponse removeGrant(Caller caller, @PathVariable Long id, @PathVariable Long agentId) {
        return WalletResponse.of(wallets.removeGrant(caller, id, agentId));
    }
}
