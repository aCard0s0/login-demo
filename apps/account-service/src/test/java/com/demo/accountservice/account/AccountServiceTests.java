package com.demo.accountservice.account;

import com.demo.accountservice.token.Caller;
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
class AccountServiceTests {

    @Autowired
    AccountService bank;

    /** Owners are account ids, so every test uses ids of its own and the order they run in cannot matter. */
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
    void aUserOwnsItsOwnAndItsAgentsAccountsAnAgentOnlyItsOwn() {
        Account mine = bank.create(user("1"), new NewAccount("savings", null));
        Account bots = bank.create(user("1"), new NewAccount("bot wallet", 7L));
        bank.deposit(user("1"), mine.getId(), 1_000L);
        bank.deposit(user("1"), bots.getId(), 100L);

        assertEquals(2, bank.list(user("1")).size(), "a user sees its own and its agents' accounts");
        assertTrue(bank.list(user("2")).isEmpty());
        assertEquals(404, status(() -> bank.get(user("2"), mine.getId())), "someone else's account is not found, not forbidden");

        Caller bot = agent("1", 7);
        assertEquals(1, bank.list(bot).size(), "an agent sees the accounts opened for it and nothing else of its owner's");
        assertEquals(404, status(() -> bank.get(bot, mine.getId())));
        assertTrue(bank.list(agent("2", 7)).isEmpty(), "an agent id alone is nothing: the owner on the token must match too");
        assertTrue(bank.list(agent("1", 8)).isEmpty());

        // The user moves money between any two of theirs; the agent only out of its own.
        bank.transfer(user("1"), mine.getId(), bots.getId(), 400L);
        assertEquals(500, bank.get(bot, bots.getId()).getBalance());
        bank.transfer(bot, bots.getId(), mine.getId(), 50L);
        assertEquals(650, bank.get(user("1"), mine.getId()).getBalance());
        assertEquals(404, status(() -> bank.transfer(bot, mine.getId(), bots.getId(), 1L)), "an agent cannot spend from an account it does not own");
        assertEquals(3, bank.transfers(user("1"), bots.getId()).size(), "one deposit and two transfers touch the bot's account");
        assertEquals("agent 7", bank.transfers(bot, bots.getId()).get(0).getBy());
    }

    @Test
    void anAgentDoesOnlyWhatItWasGrantedAndNeverGrants() {
        Account shared = bank.create(user("3"), new NewAccount("household", null));
        Account elsewhere = bank.create(user("4"), new NewAccount("shop", null));
        bank.deposit(user("3"), shared.getId(), 300L);
        Caller helper = agent("3", 11);
        Caller strangersAgent = agent("5", 12);

        assertEquals(404, status(() -> bank.get(helper, shared.getId())), "no grant, no account");

        bank.setPermission(user("3"), shared.getId(), 11L, Access.READ);
        assertEquals(300, bank.get(helper, shared.getId()).getBalance(), "READ sees it");
        assertEquals(1, bank.transfers(helper, shared.getId()).size(), "READ sees its transfers");
        assertEquals(404, status(() -> bank.transfer(helper, shared.getId(), elsewhere.getId(), 10L)), "READ does not spend");

        bank.setPermission(user("3"), shared.getId(), 11L, Access.WRITE);
        bank.transfer(helper, shared.getId(), elsewhere.getId(), 10L);
        assertEquals(290, bank.get(user("3"), shared.getId()).getBalance());
        assertEquals(10, bank.get(user("4"), elsewhere.getId()).getBalance(), "any account may be paid");

        // A grant may go to anybody's agent; the owner on its token no longer has to match.
        bank.setPermission(user("3"), shared.getId(), 12L, Access.READ);
        assertEquals(1, bank.list(strangersAgent).size());

        assertEquals(403, status(() -> bank.setPermission(helper, shared.getId(), 11L, Access.WRITE)), "an agent never grants");
        assertEquals(403, status(() -> bank.deposit(helper, shared.getId(), 1L)), "an agent never deposits");
        assertEquals(403, status(() -> bank.create(helper, new NewAccount("mine now", null))), "an agent never opens accounts");
        assertEquals(404, status(() -> bank.setPermission(user("4"), shared.getId(), 11L, Access.WRITE)), "only the owner grants");

        bank.removePermission(user("3"), shared.getId(), 11L);
        assertEquals(404, status(() -> bank.get(helper, shared.getId())), "a removed grant bites at once");
        assertEquals(404, status(() -> bank.removePermission(user("3"), shared.getId(), 11L)));
    }

    @Test
    void moneyIsNeitherMadeNorLostByATransfer() {
        Account a = bank.create(user("6"), new NewAccount("a", null));
        Account b = bank.create(user("6"), new NewAccount("b", null));
        bank.deposit(user("6"), a.getId(), 100L);

        assertEquals(400, status(() -> bank.transfer(user("6"), a.getId(), b.getId(), 101L)), "insufficient funds");
        assertEquals(400, status(() -> bank.transfer(user("6"), a.getId(), b.getId(), 0L)));
        assertEquals(400, status(() -> bank.transfer(user("6"), a.getId(), b.getId(), -5L)));
        assertEquals(400, status(() -> bank.transfer(user("6"), a.getId(), a.getId(), 5L)));
        assertEquals(404, status(() -> bank.transfer(user("6"), a.getId(), 999_999L, 5L)));
        assertEquals(400, status(() -> bank.deposit(user("6"), a.getId(), 0L)));
        assertEquals(400, status(() -> bank.create(user("6"), new NewAccount("  ", null))));

        bank.transfer(user("6"), a.getId(), b.getId(), 100L);
        assertEquals(0, bank.get(user("6"), a.getId()).getBalance());
        assertEquals(100, bank.get(user("6"), b.getId()).getBalance());
    }

    @Test
    void aModeratorReadsEveryoneAnAdminAlsoWrites() {
        Caller moderator = new Caller("7", "MODERATOR");
        Caller admin = new Caller("8", "ADMIN");
        Account theirs = bank.create(user("9"), new NewAccount("theirs", null));
        Account other = bank.create(user("10"), new NewAccount("other", null));
        bank.deposit(user("9"), theirs.getId(), 50L);

        assertTrue(bank.list(moderator).stream().anyMatch(x -> x.getId().equals(theirs.getId())));
        assertEquals(50, bank.get(moderator, theirs.getId()).getBalance());
        assertEquals(404, status(() -> bank.transfer(moderator, theirs.getId(), other.getId(), 1L)), "reading everyone is not writing everyone");

        bank.transfer(admin, theirs.getId(), other.getId(), 20L);
        bank.setPermission(admin, theirs.getId(), 99L, Access.READ);
        assertEquals(30, bank.get(user("9"), theirs.getId()).getBalance());
        assertEquals(1, bank.get(user("9"), theirs.getId()).getPermissions().size());

        assertTrue(bank.list(new Caller("11", "SUPERUSER")).isEmpty(), "an unrecognised role lands on least privilege");
    }
}
