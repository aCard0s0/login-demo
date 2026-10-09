package com.demo.accountservice.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Listing is scoped in the query itself. Single-account lookups are not: {@link AccountService} loads the row
 * and decides, because an agent's right to it may come from a permission rather than from ownership.
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    List<Account> findByOwnerOrderByIdAsc(String owner);

    /** Every account there is. Only ever reached by a caller whose role reads everyone. */
    List<Account> findAllByOrderByIdAsc();

    /** What one agent may see: the accounts opened for it by its owner, plus any account it was granted. */
    @Query("select a from Account a where (a.owner = :owner and a.agentId = :agent) "
            + "or exists (select p from AccountPermission p where p.account = a and p.agentId = :agent) order by a.id asc")
    List<Account> findReachableByAgent(@Param("owner") String owner, @Param("agent") Long agent);

    /** Takes the money only if it is there: zero rows means insufficient funds. One statement, so two transfers cannot both spend the same balance. */
    @Modifying
    @Query("update Account a set a.balance = a.balance - :amount where a.id = :id and a.balance >= :amount")
    int debit(@Param("id") Long id, @Param("amount") long amount);

    @Modifying
    @Query("update Account a set a.balance = a.balance + :amount where a.id = :id")
    int credit(@Param("id") Long id, @Param("amount") long amount);
}
