package com.bank.transactionservice.service;

import com.bank.transactionservice.config.AppConfig;
import com.bank.transactionservice.entity.DailyTransactionCount;
import com.bank.transactionservice.exception.CustomExceptions.*;
import com.bank.transactionservice.repository.DailyTransactionCountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

// Enforces two types of limits:
//
// 1. DAILY TRANSACTION COUNT LIMIT
//    - Maximum 16 transactions per SAVINGS account per day
//    - CURRENT accounts have NO daily transaction limit
//    - Applies to: deposit, withdrawal, transfer
//    - Count resets at midnight every day
//
// 2. NEW RECIPIENT LIMIT (transfers only)
//    - Destination account created < 24 hours ago → max ₹1,00,000
//    - Destination account older than 24 hours → no limit
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionLimitService {

    private final DailyTransactionCountRepository dailyCountRepository;
    private final AccountVerificationService accountVerificationService;
    private final AppConfig appConfig;

    // ── DAILY COUNT CHECK ─────────────────────────────────────────
    // CURRENT accounts skip this check entirely.
    // SAVINGS accounts are limited to 16 transactions per day.
    public void checkDailyTransactionCount(String accountNumber) {

        // Fetch account type — SAVINGS or CURRENT
        String accountType = accountVerificationService.getAccountType(accountNumber);

        // CURRENT accounts have unlimited transactions — skip check
        if ("CURRENT".equalsIgnoreCase(accountType)) {
            log.info("Daily limit check skipped for CURRENT account: {}", accountNumber);
            return;
        }

        // SAVINGS account — enforce daily limit
        LocalDate today = LocalDate.now();

        DailyTransactionCount record = dailyCountRepository
                .findByAccountNumberAndCountDate(accountNumber, today)
                .orElse(DailyTransactionCount.builder()
                        .accountNumber(accountNumber)
                        .countDate(today)
                        .transactionCount(0)
                        .build());

        int current = record.getTransactionCount();
        int limit   = appConfig.getDailyTransactionLimit();

        if (current >= limit) {
            throw new DailyLimitExceededException(
                    String.format(
                            "Daily transaction limit reached. You have used %d/%d transactions today. " +
                                    "Limit resets at midnight.",
                            current, limit)
            );
        }

        log.info("Daily count check passed for SAVINGS {}: {}/{}", accountNumber, current + 1, limit);
    }

    // ── DAILY COUNT INCREMENT ─────────────────────────────────────
    // Only increments for SAVINGS accounts.
    // CURRENT accounts do not track count.
    @Transactional
    public void incrementDailyCount(String accountNumber) {

        String accountType = accountVerificationService.getAccountType(accountNumber);

        if ("CURRENT".equalsIgnoreCase(accountType)) {
            log.info("Daily count not tracked for CURRENT account: {}", accountNumber);
            return;
        }

        LocalDate today = LocalDate.now();

        DailyTransactionCount record = dailyCountRepository
                .findByAccountNumberAndCountDate(accountNumber, today)
                .orElse(DailyTransactionCount.builder()
                        .accountNumber(accountNumber)
                        .countDate(today)
                        .transactionCount(0)
                        .build());

        record.setTransactionCount(record.getTransactionCount() + 1);
        dailyCountRepository.save(record);

        log.info("Daily count incremented for SAVINGS {}: {}/{} transactions today",
                accountNumber, record.getTransactionCount(),
                appConfig.getDailyTransactionLimit());
    }

    // ── NEW RECIPIENT CHECK ───────────────────────────────────────
    public boolean isNewRecipient(LocalDateTime accountCreatedAt) {
        LocalDateTime threshold = LocalDateTime.now()
                .minusHours(appConfig.getNewRecipientThresholdHours());
        return accountCreatedAt.isAfter(threshold);
    }

    public void checkNewRecipientLimit(LocalDateTime recipientCreatedAt,
                                       BigDecimal transferAmount) {
        if (!isNewRecipient(recipientCreatedAt)) {
            return;
        }

        BigDecimal limit = appConfig.getNewRecipientTransferLimit();

        if (transferAmount.compareTo(limit) > 0) {
            throw new NewRecipientLimitExceededException(
                    String.format(
                            "This is a new recipient account (added less than %d hours ago). " +
                                    "Maximum transfer amount is ₹%.2f for new recipients. " +
                                    "The limit will be lifted after 24 hours.",
                            appConfig.getNewRecipientThresholdHours(),
                            limit)
            );
        }

        log.info("New recipient limit check passed: ₹{} <= ₹{}", transferAmount, limit);
    }

    // ── REMAINING TRANSACTIONS ────────────────────────────────────
    // Returns -1 for CURRENT accounts (meaning unlimited)
    // Returns remaining count for SAVINGS accounts
    public int getRemainingTransactions(String accountNumber) {
        String accountType = accountVerificationService.getAccountType(accountNumber);

        if ("CURRENT".equalsIgnoreCase(accountType)) {
            return -1;  // -1 signals "unlimited" to the caller
        }

        LocalDate today = LocalDate.now();
        int used = dailyCountRepository
                .findByAccountNumberAndCountDate(accountNumber, today)
                .map(DailyTransactionCount::getTransactionCount)
                .orElse(0);
        return appConfig.getDailyTransactionLimit() - used;
    }
}
