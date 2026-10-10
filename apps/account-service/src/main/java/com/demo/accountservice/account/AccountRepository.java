package com.demo.accountservice.account;

import com.demo.accountservice.account.entities.Account;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Listing is scoped in the query itself, and fetches the permissions in the same query: left to the eager
 * mapping, a list of N accounts would load them with N more selects. Single-account lookups are not: {@link AccountService} loads the row
 * and decides, because an agent's right to it may come from a permission rather than from ownership.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    @EntityGraph(attributePaths = "permissions")
    List<Account> findByOwnerOrderByIdAsc(String owner);

    /** Every account there is. Only ever reached by a caller whose role reads everyone. */
    @EntityGraph(attributePaths = "permissions")
    List<Account> findAllByOrderByIdAsc();

    /** What one agent may see: the accounts opened for it by its owner, plus any account it was granted. */
    @EntityGraph(attributePaths = "permissions")
    @Query("select a from Account a where (a.owner = :owner and a.agentId = :agent) "
            + "or exists (select p from AccountPermission p where p.account = a and p.agentId = :agent) order by a.id asc")
    List<Account> findReachableByAgent(@Param("owner") String owner, @Param("agent") Long agent);

    /**
     * Takes the money only if it is there: zero rows means insufficient funds. One statement, so two transfers
     * cannot both spend the same balance. Clears the persistence context afterwards, so an {@link Account} loaded
     * earlier in the transaction cannot be flushed back with its stale balance.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Account a set a.balance = a.balance - :amount where a.id = :id and a.balance >= :amount")
    int debit(@Param("id") Long id, @Param("amount") long amount);

    @Modifying(clearAutomatically = true)
    @Query("update Account a set a.balance = a.balance + :amount where a.id = :id")
    int credit(@Param("id") Long id, @Param("amount") long amount);
}
