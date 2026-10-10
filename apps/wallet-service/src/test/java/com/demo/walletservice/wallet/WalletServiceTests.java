package com.demo.walletservice.wallet;

import com.demo.walletservice.wallet.dto.Access;
import com.demo.walletservice.wallet.dto.NewWallet;
import com.demo.walletservice.wallet.entities.Wallet;
import com.demo.auth.client.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Inline rather than a test application.properties, which would shadow the main one instead of merging over it.
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test.db",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // SQLite allows a single writer; one connection keeps Hibernate from tripping over itself.
        "spring.datasource.hikari.maximum-pool-size=1",
})
class WalletServiceTests {

    @Autowired
    WalletService service;

    /** Owners are wallet ids, so every test uses ids of its own and the order they run in cannot matter. */
    private static Caller user(String id) {
        return new Caller(id, "USER");
    }

    /** An agent token: the owner as subject, pinned to one agent. */
    private static Caller agent(String owner, long agent) {
        return new Caller(owner, "AGENT", agent);
    }

    private static int status(Runnable call) {
        return assertThrows(ResponseStatusException.class, call::run).getStatusCode().value();
    }

    @Test
    void aUserOwnsItsOwnAndItsAgentsWalletsAnAgentOnlyItsOwn() {
        Wallet mine = service.create(user("1"), new NewWallet("savings", null));
        Wallet bots = service.create(user("1"), new NewWallet("bot wallet", 7L));
        service.deposit(user("1"), mine.getId(), 1_000L);
        service.deposit(user("1"), bots.getId(), 100L);

        assertEquals(2, service.list(user("1")).size(), "a user sees its own and its agents' wallets");
        assertTrue(service.list(user("2")).isEmpty());
        assertEquals(404, status(() -> service.get(user("2"), mine.getId())), "someone else's wallet is not found, not forbidden");

        Caller bot = agent("1", 7);
        assertEquals(1, service.list(bot).size(), "an agent sees the wallets opened for it and nothing else of its owner's");
        assertEquals(404, status(() -> service.get(bot, mine.getId())));
        assertTrue(service.list(agent("2", 7)).isEmpty(), "an agent id alone is nothing: the owner on the token must match too");
        assertTrue(service.list(agent("1", 8)).isEmpty());

        // The user moves money between any two of theirs; the agent only out of its own.
        service.transfer(user("1"), mine.getId(), bots.getId(), 400L);
        assertEquals(500, service.get(bot, bots.getId()).getBalance());
        service.transfer(bot, bots.getId(), mine.getId(), 50L);
        assertEquals(650, service.get(user("1"), mine.getId()).getBalance());
        assertEquals(404, status(() -> service.transfer(bot, mine.getId(), bots.getId(), 1L)), "an agent cannot spend from a wallet it does not own");
        assertEquals(3, service.transfers(user("1"), bots.getId()).size(), "one deposit and two transfers touch the bot's wallet");
        assertEquals("agent 7", service.transfers(bot, bots.getId()).get(0).getBy());
    }

    @Test
    void anAgentDoesOnlyWhatItWasGrantedAndNeverGrants() {
        Wallet shared = service.create(user("3"), new NewWallet("household", null));
        Wallet elsewhere = service.create(user("4"), new NewWallet("shop", null));
        service.deposit(user("3"), shared.getId(), 300L);
        Caller helper = agent("3", 11);
        Caller strangersAgent = agent("5", 12);

        assertEquals(404, status(() -> service.get(helper, shared.getId())), "no grant, no wallet");

        service.setGrant(user("3"), shared.getId(), 11L, Access.READ);
        assertEquals(300, service.get(helper, shared.getId()).getBalance(), "READ sees it");
        assertEquals(1, service.transfers(helper, shared.getId()).size(), "READ sees its transfers");
        assertEquals(404, status(() -> service.transfer(helper, shared.getId(), elsewhere.getId(), 10L)), "READ does not spend");

        service.setGrant(user("3"), shared.getId(), 11L, Access.WRITE);
        service.transfer(helper, shared.getId(), elsewhere.getId(), 10L);
        assertEquals(290, service.get(user("3"), shared.getId()).getBalance());
        assertEquals(10, service.get(user("4"), elsewhere.getId()).getBalance(), "any wallet may be paid");

        // A grant may go to anybody's agent; the owner on its token no longer has to match.
        service.setGrant(user("3"), shared.getId(), 12L, Access.READ);
        assertEquals(1, service.list(strangersAgent).size());

        assertEquals(403, status(() -> service.setGrant(helper, shared.getId(), 11L, Access.WRITE)), "an agent never grants");
        assertEquals(403, status(() -> service.deposit(helper, shared.getId(), 1L)), "an agent never deposits");
        assertEquals(403, status(() -> service.create(helper, new NewWallet("mine now", null))), "an agent never opens wallets");
        assertEquals(404, status(() -> service.setGrant(user("4"), shared.getId(), 11L, Access.WRITE)), "only the owner grants");

        service.removeGrant(user("3"), shared.getId(), 11L);
        assertEquals(404, status(() -> service.get(helper, shared.getId())), "a removed grant bites at once");
        assertEquals(404, status(() -> service.removeGrant(user("3"), shared.getId(), 11L)));
    }

    @Test
    void moneyIsNeitherMadeNorLostByATransfer() {
        Wallet a = service.create(user("6"), new NewWallet("a", null));
        Wallet b = service.create(user("6"), new NewWallet("b", null));
        service.deposit(user("6"), a.getId(), 100L);

        assertEquals(400, status(() -> service.transfer(user("6"), a.getId(), b.getId(), 101L)), "insufficient funds");
        assertEquals(400, status(() -> service.transfer(user("6"), a.getId(), b.getId(), 0L)));
        assertEquals(400, status(() -> service.transfer(user("6"), a.getId(), b.getId(), -5L)));
        assertEquals(400, status(() -> service.transfer(user("6"), a.getId(), a.getId(), 5L)));
        assertEquals(404, status(() -> service.transfer(user("6"), a.getId(), 999_999L, 5L)));
        assertEquals(400, status(() -> service.transfer(user("6"), a.getId(), 999_999L, 101L)),
                "unaffordable is 400 whether or not the destination exists: no free probing for ids");
        assertEquals(100, service.get(user("6"), a.getId()).getBalance(), "a refused transfer rolls back its other half");
        assertEquals(400, status(() -> service.deposit(user("6"), a.getId(), 0L)));
        assertEquals(400, status(() -> service.create(user("6"), new NewWallet("  ", null))));

        service.transfer(user("6"), a.getId(), b.getId(), 100L);
        assertEquals(0, service.get(user("6"), a.getId()).getBalance());
        assertEquals(100, service.get(user("6"), b.getId()).getBalance());
    }

    @Test
    void aModeratorReadsEveryoneAnAdminAlsoWrites() {
        Caller moderator = new Caller("7", "MODERATOR");
        Caller admin = new Caller("8", "ADMIN");
        Wallet theirs = service.create(user("9"), new NewWallet("theirs", null));
        Wallet other = service.create(user("10"), new NewWallet("other", null));
        service.deposit(user("9"), theirs.getId(), 50L);

        assertTrue(service.list(moderator).stream().anyMatch(x -> x.getId().equals(theirs.getId())));
        assertEquals(50, service.get(moderator, theirs.getId()).getBalance());
        assertEquals(404, status(() -> service.transfer(moderator, theirs.getId(), other.getId(), 1L)), "reading everyone is not writing everyone");

        service.transfer(admin, theirs.getId(), other.getId(), 20L);
        service.setGrant(admin, theirs.getId(), 99L, Access.READ);
        assertEquals(30, service.get(user("9"), theirs.getId()).getBalance());
        assertEquals(1, service.get(user("9"), theirs.getId()).getGrants().size());

        assertTrue(service.list(new Caller("11", "SUPERUSER")).isEmpty(), "an unrecognised role lands on least privilege");
    }
}
