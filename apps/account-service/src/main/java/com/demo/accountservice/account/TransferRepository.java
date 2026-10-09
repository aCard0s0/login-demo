package com.demo.accountservice.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    // ponytail: newest 100 only, so a busy account cannot flood the page or an agent's context. Page with an
    // id cursor ("before") when older history is needed.
    List<Transfer> findTop100ByFromAccountOrToAccountOrderByIdDesc(Long from, Long to);
}
