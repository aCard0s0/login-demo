package com.demo.walletservice.wallet;

import com.demo.walletservice.wallet.dto.Access;
import com.demo.walletservice.wallet.dto.NewWallet;
import com.demo.walletservice.wallet.entities.*;
import com.demo.auth.client.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The one place that decides who may see and move what. Four rules:
 *
 * <ul>
 *   <li>A <b>user</b> owns every wallet whose {@code owner} is their id -- the ones opened for themselves and the
 *       ones opened for their agents alike -- and may read, deposit into, transfer from and grant on all of them.</li>
 *   <li>An <b>agent</b> owns only the wallets its owner opened for that very agent (same owner, same agent id).
 *       Anything else it reaches through a grant: READ to see, WRITE to also transfer from.</li>
 *   <li>Only a user opens wallets, deposits, and grants or removes grants. An agent token is 403 there,
 *       since an agent that could widen its own access would make the grants meaningless.</li>
 *   <li>Admin reads and writes everyone; moderator reads everyone. The destination of a transfer may be any
 *       wallet at all -- paying somebody is the point -- so only the source is checked.</li>
 * </ul>
 *
 * <p>A wallet the caller may not see comes back 404, not 403, so neither answer says whether it exists.
 */
@Service
public class WalletService {

    private final WalletRepository wallets;
    private final TransferRepository transfers;

    public WalletService(WalletRepository wallets, TransferRepository transfers) {
        this.wallets = wallets;
        this.transfers = transfers;
    }

    /** Totals across every wallet. Public: they give away nobody's balance. */
    public long count() {
        return wallets.count();
    }

    public long transferCount() {
        return transfers.count();
    }

    public List<Wallet> list(Caller caller) {
        if (caller.isAgent()) {
            return wallets.findReachableByAgent(caller.userId(), caller.agentId());
        }
        return caller.readsEveryone() ? wallets.findAllByOrderByIdAsc() : wallets.findByOwnerOrderByIdAsc(caller.userId());
    }

    /** The wallet, if this caller may at least read it; else 404. */
    public Wallet get(Caller caller, Long id) {
        Wallet wallet = wallets.findById(id).orElseThrow(WalletService::notFound);
        boolean granted = caller.isAgent() && grant(wallet, caller.agentId()) != null;
        if (!(caller.readsEveryone() || owns(caller, wallet) || granted)) {
            throw notFound();
        }
        return wallet;
    }

    public List<Transfer> transfers(Caller caller, Long id) {
        Long wallet = get(caller, id).getId();
        return transfers.findTop100ByFromWalletOrToWalletOrderByIdDesc(wallet, wallet);
    }

    /** Opens a wallet for the caller, or for one of the caller's agents when {@code agentId} is given. */
    public Wallet create(Caller caller, NewWallet in) {
        userOnly(caller);
        return wallets.save(new Wallet(caller.userId(), in.agentId(), cleanName(in.name())));
    }

    @Transactional
    public Transfer deposit(Caller caller, Long id, Long amount) {
        Wallet wallet = managed(caller, id);
        wallets.credit(wallet.getId(), positive(amount));
        return transfers.save(new Transfer(null, wallet.getId(), amount, caller.describe()));
    }

    @Transactional
    public Transfer transfer(Caller caller, Long from, Long to, Long amount) {
        Wallet source = writable(caller, from);
        long cents = positive(amount);
        if (to == null) {
            throw noSuchDestination();
        }
        long src = source.getId();
        if (src == to) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source and destination are the same wallet");
        }
        // Both rows are updated in ascending id order, so A->B and B->A racing cannot deadlock on Postgres. Funds
        // are judged before the destination: an unaffordable transfer is 400 whether or not that wallet exists,
        // so the answer cannot be used to probe for ids for free. Either refusal rolls back the other update.
        int debited, credited;
        if (src < to) {
            debited = wallets.debit(src, cents);
            credited = wallets.credit(to, cents);
        } else {
            credited = wallets.credit(to, cents);
            debited = wallets.debit(src, cents);
        }
        if (debited == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insufficient funds");
        }
        if (credited == 0) {
            throw noSuchDestination();
        }
        return transfers.save(new Transfer(src, to, cents, caller.describe()));
    }

    /** Grants, or changes, what one agent may do with this wallet. Only the owner (or an admin) may. */
    @Transactional
    public Wallet setGrant(Caller caller, Long id, Long agentId, Access access) {
        Wallet wallet = managed(caller, id);
        if (access == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "access must be READ or WRITE");
        }
        if (agentId != null && agentId.equals(wallet.getAgentId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "that agent already owns this wallet");
        }
        WalletGrant existing = grant(wallet, agentId);
        if (existing == null) {
            wallet.getGrants().add(new WalletGrant(wallet, agentId, access));
        } else {
            existing.setAccess(access);
        }
        return wallets.save(wallet);
    }

    @Transactional
    public Wallet removeGrant(Caller caller, Long id, Long agentId) {
        Wallet wallet = managed(caller, id);
        WalletGrant existing = grant(wallet, agentId);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such grant");
        }
        wallet.getGrants().remove(existing);
        return wallets.save(wallet);
    }

    /** A wallet this caller may move money out of, or 404. */
    private Wallet writable(Caller caller, Long id) {
        Wallet wallet = get(caller, id);
        WalletGrant grant = caller.isAgent() ? grant(wallet, caller.agentId()) : null;
        boolean granted = grant != null && grant.getAccess() == Access.WRITE;
        if (!(caller.writesEveryone() || owns(caller, wallet) || granted)) {
            throw notFound();
        }
        return wallet;
    }

    /** A wallet this caller administers: deposits and grants. Never an agent, whatever it was granted. */
    private Wallet managed(Caller caller, Long id) {
        userOnly(caller);
        return writable(caller, id);
    }

    private static boolean owns(Caller caller, Wallet wallet) {
        return wallet.getOwner().equals(caller.userId())
                && (!caller.isAgent() || caller.agentId().equals(wallet.getAgentId()));
    }

    private static WalletGrant grant(Wallet wallet, Long agentId) {
        return wallet.getGrants().stream().filter(p -> p.getAgentId().equals(agentId)).findFirst().orElse(null);
    }

    private static void userOnly(Caller caller) {
        if (caller.isAgent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "an agent can only read wallets and transfer from them");
        }
    }

    /** Ten trillion in major units: far above any balance here, far below where a bigint sum could overflow. */
    static final long MAX_AMOUNT = 1_000_000_000_000_000L;

    private static long positive(Long amount) {
        if (amount == null || amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be a positive number of cents");
        }
        if (amount > MAX_AMOUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount is too large");
        }
        return amount;
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (name.strip().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is too long");
        }
        return name.strip();
    }

    private static ResponseStatusException noSuchDestination() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such destination wallet");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such wallet");
    }
}
