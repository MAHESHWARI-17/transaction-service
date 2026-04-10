package com.bank.transactionservice.dto.response;

import com.bank.transactionservice.entity.Transaction;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class TransactionResponse {

    private String transactionRef;
    private String fromAccount;
    private String toAccount;
    private String transactionType;

    // Amount the user intended (before fee)
    private BigDecimal amount;

    // Fee charged on this transaction
    private BigDecimal feeAmount;

    // Fee rate applied as percentage e.g. 2.5 or 5.0
    private BigDecimal feeRate;

    // Total actually debited = amount + fee (0 for deposits)
    private BigDecimal totalDebited;

    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String status;
    private String description;

    // Whether new-recipient limit was applied on this transfer
    private boolean newRecipientFlag;

    private LocalDateTime createdAt;

    // Convenience message shown to user in response
    private String summary;

    public static TransactionResponse from(Transaction t) {
        String summary = buildSummary(t);
        return TransactionResponse.builder()
                .transactionRef(t.getTransactionRef())
                .fromAccount(t.getFromAccount())
                .toAccount(t.getToAccount())
                .transactionType(t.getTransactionType().name())
                .amount(t.getAmount())
                .feeAmount(t.getFeeAmount())
                .feeRate(t.getFeeRate())
                .totalDebited(t.getTotalDebited())
                .balanceBefore(t.getBalanceBefore())
                .balanceAfter(t.getBalanceAfter())
                .status(t.getStatus().name())
                .description(t.getDescription())
                .newRecipientFlag(t.isNewRecipientFlag())
                .createdAt(t.getCreatedAt())
                .summary(summary)
                .build();
    }

    private static String buildSummary(Transaction t) {
        return switch (t.getTransactionType()) {
            case DEPOSIT -> String.format(
                    "Deposited ₹%.2f. New balance: ₹%.2f",
                    t.getAmount(), t.getBalanceAfter());
            case WITHDRAWAL -> String.format(
                    "Withdrawn ₹%.2f (Fee: ₹%.2f @ %.1f%%). Total debited: ₹%.2f. New balance: ₹%.2f",
                    t.getAmount(), t.getFeeAmount(), t.getFeeRate(),
                    t.getTotalDebited(), t.getBalanceAfter());
            case TRANSFER -> String.format(
                    "Transferred ₹%.2f to %s (Fee: ₹%.2f @ %.1f%%). Total debited: ₹%.2f. New balance: ₹%.2f%s",
                    t.getAmount(), t.getToAccount(),
                    t.getFeeAmount(), t.getFeeRate(),
                    t.getTotalDebited(), t.getBalanceAfter(),
                    t.isNewRecipientFlag() ? " [New recipient limit applied]" : "");
        };
    }
}
