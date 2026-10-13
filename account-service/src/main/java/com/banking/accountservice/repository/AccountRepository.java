package com.banking.accountservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.banking.accountservice.entity.Account;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, String> {

    boolean existsByEmail(String email);

    boolean existsByAccountNumber (String accountNumber);

    Optional<Account> findByAccountNumber(String accountNumber);

}
