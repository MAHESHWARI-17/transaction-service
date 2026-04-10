package com.bank.transactionservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.UUID;

// Tracks how many transactions an account has made today.
// Limit is 16 transactions per account per day (deposits + withdrawals + transfers all count).
// No cap on amount — only the count is limited.
@Entity
@Table(name = "daily_transaction_counts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"account_number", "count_date"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailyTransactionCount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID countId;

    @Column(name = "account_number", nullable = false, length = 20)
    private String accountNumber;

    // The date this count belongs to (resets every day automatically — new row each day)
    @Column(name = "count_date", nullable = false)
    private LocalDate countDate;

    // How many transactions done today — max 16
    @Column(name = "transaction_count", nullable = false)
    @Builder.Default
    private int transactionCount = 0;
}
