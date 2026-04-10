package com.bank.transactionservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_from_account", columnList = "from_account"),
    @Index(name = "idx_to_account",   columnList = "to_account"),
    @Index(name = "idx_txn_ref",      columnList = "transaction_ref"),
    @Index(name = "idx_initiated_by", columnList = "initiated_by")
})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "transaction_id", updatable = false, nullable = false)
    private UUID transactionId;

    // Human-readable reference e.g. TXN202603230001
    @Column(name = "transaction_ref", nullable = false, unique = true, length = 20)
    private String transactionRef;

    // Source account — null for DEPOSIT (money coming in from outside)
    @Column(name = "from_account", length = 20)
    private String fromAccount;

    // Destination account — null for WITHDRAWAL (money going out to outside)
    @Column(name = "to_account", length = 20)
    private String toAccount;

    @Column(name = "transaction_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TransactionType transactionType;

    // The amount the user intended to send/deposit/withdraw
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    // Fee charged on this transaction (0 for deposits)
    @Column(name = "fee_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal feeAmount = BigDecimal.ZERO;

    // Fee percentage applied e.g. 2.5 or 5.0
    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal feeRate = BigDecimal.ZERO;

    // Total amount debited from account = amount + fee
    @Column(name = "total_debited", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDebited;

    // Balance of the primary account (from_account for debit, to_account for deposit)
    @Column(name = "balance_before", nullable = false, precision = 18, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 18, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    // Optional description e.g. "Rent payment", "Grocery"
    @Column(name = "description", length = 255)
    private String description;

    // Failure reason — set only when status = FAILED
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    // customer_id of the person who initiated the transaction
    @Column(name = "initiated_by", nullable = false, length = 15)
    private String initiatedBy;

    // Whether the recipient was a new account (added < 24 hrs ago) at time of transfer
    @Column(name = "new_recipient_flag", nullable = false)
    @Builder.Default
    private boolean newRecipientFlag = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum TransactionType {
        DEPOSIT,     // Money added to an account
        WITHDRAWAL,  // Money taken out of an account
        TRANSFER     // Money moved from one account to another
    }

    public enum TransactionStatus {
        SUCCESS,
        FAILED,
        PENDING
    }
}
