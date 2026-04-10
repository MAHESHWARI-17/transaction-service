package com.bank.transactionservice.repository;

import com.bank.transactionservice.entity.DailyTransactionCount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DailyTransactionCountRepository extends JpaRepository<DailyTransactionCount, UUID> {

    Optional<DailyTransactionCount> findByAccountNumberAndCountDate(
            String accountNumber, LocalDate countDate);
}
