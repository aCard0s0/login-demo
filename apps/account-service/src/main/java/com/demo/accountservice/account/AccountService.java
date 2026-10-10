package com.demo.accountservice.account;

import com.demo.accountservice.account.dto.Access;
import com.demo.accountservice.account.dto.NewAccount;
import com.demo.accountservice.account.entities.*;
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
 *   <li>A <b>user</b> owns every account whose {@code owner} is their id -- the ones opened for themselves and the
 *       ones opened for their agents alike -- and may read, deposit into, transfer from and grant on all of them.</li>
 *   <li>An <b>agent</b> owns only the accounts its owner opened for that very agent (same owner, same agent id).
 *       Anything else it reaches through a permission: READ to see, WRITE to also transfer from.</li>
 *   <li>Only a user opens accounts, deposits, and grants or removes permissions. An agent token is 403 there,
 *       since an agent that could widen its own access would make the permissions meaningless.</li>
 *   <li>Admin reads and writes everyone; moderator reads everyone. The destination of a transfer may be any
 *       account at all -- paying somebody is the point -- so only the source is checked.</li>
 * </ul>
 *
 * <p>An account the caller may not see comes back 404, not 403, so neither answer says whether it exists.
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;

    public AccountService(AccountRepository accounts, TransferRepository transfers) {
        this.accounts = accounts;
        this.transfers = transfers;
    }

    /** Totals across every account. Public: they give away nobody's balance. */
    public long count() {
        return accounts.count();
    }

    public long transferCount() {
        return transfers.count();
    }

    public List<Account> list(Caller caller) {
        if (caller.isAgent()) {
            return accounts.findReachableByAgent(caller.userId(), caller.agentId());
        }
        return caller.readsEveryone() ? accounts.findAllByOrderByIdAsc() : accounts.findByOwnerOrderByIdAsc(caller.userId());
    }

    /** The account, if this caller may at least read it; else 404. */
    public Account get(Caller caller, Long id) {
        Account account = accounts.findById(id).orElseThrow(AccountService::notFound);
        boolean granted = caller.isAgent() && grant(account, caller.agentId()) != null;
        if (!(caller.readsEveryone() || owns(caller, account) || granted)) {
            throw notFound();
        }
        return account;
    }

    public List<Transfer> transfers(Caller caller, Long id) {
        Long account = get(caller, id).getId();
        return transfers.findTop100ByFromAccountOrToAccountOrderByIdDesc(account, account);
    }

    /** Opens an account for the caller, or for one of the caller's agents when {@code agentId} is given. */
    public Account create(Caller caller, NewAccount in) {
        userOnly(caller);
        return accounts.save(new Account(caller.userId(), in.agentId(), cleanName(in.name())));
    }

    @Transactional
    public Transfer deposit(Caller caller, Long id, Long amount) {
        Account account = managed(caller, id);
        accounts.credit(account.getId(), positive(amount));
        return transfers.save(new Transfer(null, account.getId(), amount, caller.describe()));
    }

    @Transactional
    public Transfer transfer(Caller caller, Long from, Long to, Long amount) {
        Account source = writable(caller, from);
        long cents = positive(amount);
        if (to == null) {
            throw noSuchDestination();
        }
        long src = source.getId();
        if (src == to) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source and destination are the same account");
        }
        // Both rows are updated in ascending id order, so A->B and B->A racing cannot deadlock on Postgres. Funds
        // are judged before the destination: an unaffordable transfer is 400 whether or not that account exists,
        // so the answer cannot be used to probe for ids for free. Either refusal rolls back the other update.
        int debited, credited;
        if (src < to) {
            debited = accounts.debit(src, cents);
            credited = accounts.credit(to, cents);
        } else {
            credited = accounts.credit(to, cents);
            debited = accounts.debit(src, cents);
        }
        if (debited == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "insufficient funds");
        }
        if (credited == 0) {
            throw noSuchDestination();
        }
        return transfers.save(new Transfer(src, to, cents, caller.describe()));
    }

    /** Grants, or changes, what one agent may do with this account. Only the owner (or an admin) may. */
    @Transactional
    public Account setPermission(Caller caller, Long id, Long agentId, Access access) {
        Account account = managed(caller, id);
        if (access == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "access must be READ or WRITE");
        }
        if (agentId != null && agentId.equals(account.getAgentId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "that agent already owns this account");
        }
        AccountPermission existing = grant(account, agentId);
        if (existing == null) {
            account.getPermissions().add(new AccountPermission(account, agentId, access));
        } else {
            existing.setAccess(access);
        }
        return accounts.save(account);
    }

    @Transactional
    public Account removePermission(Caller caller, Long id, Long agentId) {
        Account account = managed(caller, id);
        AccountPermission existing = grant(account, agentId);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such permission");
        }
        account.getPermissions().remove(existing);
        return accounts.save(account);
    }

    /** An account this caller may move money out of, or 404. */
    private Account writable(Caller caller, Long id) {
        Account account = get(caller, id);
        AccountPermission grant = caller.isAgent() ? grant(account, caller.agentId()) : null;
        boolean granted = grant != null && grant.getAccess() == Access.WRITE;
        if (!(caller.writesEveryone() || owns(caller, account) || granted)) {
            throw notFound();
        }
        return account;
    }

    /** An account this caller administers: deposits and permissions. Never an agent, whatever it was granted. */
    private Account managed(Caller caller, Long id) {
        userOnly(caller);
        return writable(caller, id);
    }

    private static boolean owns(Caller caller, Account account) {
        return account.getOwner().equals(caller.userId())
                && (!caller.isAgent() || caller.agentId().equals(account.getAgentId()));
    }

    private static AccountPermission grant(Account account, Long agentId) {
        return account.getPermissions().stream().filter(p -> p.getAgentId().equals(agentId)).findFirst().orElse(null);
    }

    private static void userOnly(Caller caller) {
        if (caller.isAgent()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "an agent can only read accounts and transfer from them");
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
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such destination account");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such account");
    }
}
