package com.bank.transactionservice.repository;

import com.bank.transactionservice.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // All transactions involving an account (sent or received), newest first
    @Query("SELECT t FROM Transaction t WHERE t.fromAccount = :accNo OR t.toAccount = :accNo ORDER BY t.createdAt DESC")
    Page<Transaction> findByAccount(@Param("accNo") String accNo, Pageable pageable);

    // Transactions within a date range for a specific account (audit)
    @Query("SELECT t FROM Transaction t WHERE (t.fromAccount = :accNo OR t.toAccount = :accNo) AND t.createdAt BETWEEN :from AND :to ORDER BY t.createdAt DESC")
    List<Transaction> findByAccountAndDateRange(
            @Param("accNo") String accNo,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // All transactions initiated by a customer within a date range (compliance audit)
    @Query("SELECT t FROM Transaction t WHERE t.initiatedBy = :customerId AND t.createdAt BETWEEN :from AND :to ORDER BY t.createdAt DESC")
    List<Transaction> findByInitiatedByAndDateRange(
            @Param("customerId") String customerId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    Optional<Transaction> findByTransactionRef(String transactionRef);

    // Count today's transactions — used to generate sequential reference numbers
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.createdAt >= :startOfDay AND t.createdAt < :endOfDay")
    long countTodayTransactions(
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("endOfDay") LocalDateTime endOfDay);
}
