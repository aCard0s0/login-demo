package com.demo.walletservice.wallet.entities;

import com.demo.walletservice.wallet.WalletService;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Listing is scoped in the query itself, and fetches the grants in the same query: left to the eager
 * mapping, a list of N wallets would load them with N more selects. Single-wallet lookups are not: {@link WalletService} loads the row
 * and decides, because an agent's right to it may come from a grant rather than from ownership.
 */
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    @EntityGraph(attributePaths = "grants")
    List<Wallet> findByOwnerOrderByIdAsc(String owner);

    /** Every wallet there is. Only ever reached by a caller whose role reads everyone. */
    @EntityGraph(attributePaths = "grants")
    List<Wallet> findAllByOrderByIdAsc();

    /** What one agent may see: the wallets opened for it by its owner, plus any wallet it was granted. */
    @EntityGraph(attributePaths = "grants")
    @Query("select a from Wallet a where (a.owner = :owner and a.agentId = :agent) "
            + "or exists (select p from WalletGrant p where p.wallet = a and p.agentId = :agent) order by a.id asc")
    List<Wallet> findReachableByAgent(@Param("owner") String owner, @Param("agent") Long agent);

    /**
     * Takes the money only if it is there: zero rows means insufficient funds. One statement, so two transfers
     * cannot both spend the same balance. Clears the persistence context afterwards, so a {@link Wallet} loaded
     * earlier in the transaction cannot be flushed back with its stale balance.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Wallet a set a.balance = a.balance - :amount where a.id = :id and a.balance >= :amount")
    int debit(@Param("id") Long id, @Param("amount") long amount);

    @Modifying(clearAutomatically = true)
    @Query("update Wallet a set a.balance = a.balance + :amount where a.id = :id")
    int credit(@Param("id") Long id, @Param("amount") long amount);
}
