package com.demo.walletservice.wallet.entities;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    // ponytail: newest 100 only, so a busy wallet cannot flood the page or an agent's context. Page with an
    // id cursor ("before") when older history is needed.
    List<Transfer> findTop100ByFromWalletOrToWalletOrderByIdDesc(Long from, Long to);
}
