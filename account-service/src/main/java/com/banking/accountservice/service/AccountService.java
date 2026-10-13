package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Service
@Slf4j
@RequiredArgsConstructor 
public class AccountService {

    private final AccountRepository accountRepository;
    private static SecureRandom secureRandom = new SecureRandom();

    public AccountResponse createAccount(CreateAccountRequest request) {
        log.info("Creating account for : {}", request.getEmail());

        if (accountRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Account with email " + request.getEmail() + " already exists");
        }

        Account account = new Account();
        account.setAccountHolderName(request.getAccountHolderName());
        account.setEmail(request.getEmail());
        account.setPhone(request.getPhone());
        account.setAccountType(request.getAccountType());
        account.setAccountStatus(AccountStatus.ACTIVE);
        account.setBalance(request.getInitialDeposit());
        account.setAccountNumber(generateAccountNumber());
        account.setDailyTransactionLimit(
            request.getAccountType()== AccountType.SAVINGS 
            ? new BigDecimal("100000") : new BigDecimal("500000"));
        

        Account savedAccount = accountRepository.save(account);
        log.info("Account created successfully for : {}", savedAccount.getEmail());
        return mapToResponse(savedAccount);
    }

    public AccountResponse getAccount (String AccountNumber) {
        Account account = accountRepository.findByAccountNumber(AccountNumber)
                .orElseThrow(() -> new RuntimeException("Account number " + AccountNumber + " not found"));
        return mapToResponse(account);
    }

    public BigDecimal getBalance (String AccountNumber) {
        Account account = accountRepository.findByAccountNumber(AccountNumber)
                .orElseThrow(() -> new RuntimeException("Account number " + AccountNumber + " not found"));
        return account.getBalance();
    }

    /*
    Block Account called by fraud detection service via kafka
     */

    public void blockAccount (String AccountNumber) {
        log.info("Blocking account : {}", AccountNumber);
        Account account = accountRepository.findByAccountNumber(AccountNumber)
                .orElseThrow(() -> new RuntimeException("Account number " + AccountNumber + " not found"));
        account.setAccountStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
        log.info("Account blocked : {}", AccountNumber);
    }

    /*
    Saga step 1 - deduct balance
    Called by transaction service when transfer is initiated.
    */

    public void deductBalance (String AccountNumber, BigDecimal amount) {
        log.info("Deducting balance {} from account : {}", amount, AccountNumber);
        Account account = accountRepository.findByAccountNumber(AccountNumber)
                .orElseThrow(() -> new RuntimeException("Account number " + AccountNumber + " not found"));
        if (account.getAccountStatus() !=  AccountStatus.ACTIVE) {
            throw new RuntimeException("Account number " + AccountNumber + " is not active");
        }

        if (account.getBalance().compareTo(amount) < 0) {
            throw new RuntimeException("Insufficient funds for account : " + AccountNumber);
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
        log.info("Balance Updated. New Balance : {}", account.getBalance());
    }

    /*
    Saga step 4 - compensating transaction endpoint
    Called by transaction service in two scenarios:
    1. fraud detected => refund sender
    2. transaction completed => credit receiver
    */

    public void creditBalance (String AccountNumber, BigDecimal amount) {
        log.info("Credit balance {} to account : {}", amount, AccountNumber);
        Account account = accountRepository.findByAccountNumber(AccountNumber)
                .orElseThrow(() -> new RuntimeException("Account number " + AccountNumber + " not found"));
        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
        log.info("Balance credited. New Balance : {}", account.getBalance());
    }

    private String generateAccountNumber() {
        String accountNumber;
        do {
            long number = secureRandom.nextLong(1_000_000_000_000L);
            accountNumber = String.format("%012d", number);
        } while(accountRepository.existsByAccountNumber(accountNumber));

        return  accountNumber;
    }

    private AccountResponse mapToResponse(Account account) {
        AccountResponse response = new AccountResponse();
        response.setId(account.getId());
        response.setAccountNumber(account.getAccountNumber());
        response.setAccountHolderName(account.getAccountHolderName());
        response.setEmail(account.getEmail());
        response.setPhone(account.getPhone());
        response.setAccountType(account.getAccountType());
        response.setAccountStatus(account.getAccountStatus());
        response.setBalance(account.getBalance());
        response.setDailyTransactionLimit(account.getDailyTransactionLimit());
        response.setCreatedAt(account.getCreatedAt());
        return response;
    }

}
