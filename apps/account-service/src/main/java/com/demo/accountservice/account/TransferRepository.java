package com.demo.accountservice.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    List<Transfer> findByFromAccountOrToAccountOrderByIdDesc(Long from, Long to);
}
