package com.bank.transactionservice.service;

import com.bank.transactionservice.entity.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final RestTemplate restTemplate;
    private final AccountVerificationService accountVerificationService;

    @Value("${audit-service.url:http://localhost:8093}")
    private String auditServiceUrl;

    @Value("${audit-service.internal-api-key:internal-service-secret-key}")
    private String internalApiKey;

    // ── DEPOSIT ───────────────────────────────────────────────────
    @Async
    public void logDeposit(Transaction txn) {
        String name  = safeGetName(txn.getToAccount());
        String email = safeGetEmail(txn.getToAccount());

        send(buildPayload(
                txn.getTransactionRef(), "DEPOSIT", txn.getStatus().name(),
                null, txn.getToAccount(),
                txn.getInitiatedBy(), email, name,
                txn.getAmount(), BigDecimal.ZERO, BigDecimal.ZERO, txn.getAmount(),
                txn.getBalanceBefore(), txn.getBalanceAfter(),
                txn.getDescription(), txn.getCreatedAt()
        ), "DEPOSIT_" + txn.getStatus().name());
    }

    // ── WITHDRAWAL ────────────────────────────────────────────────
    @Async
    public void logWithdrawal(Transaction txn) {
        String name  = safeGetName(txn.getFromAccount());
        String email = safeGetEmail(txn.getFromAccount());

        send(buildPayload(
                txn.getTransactionRef(), "WITHDRAWAL", txn.getStatus().name(),
                txn.getFromAccount(), null,
                txn.getInitiatedBy(), email, name,
                txn.getAmount(), txn.getFeeAmount(), txn.getFeeRate(), txn.getTotalDebited(),
                txn.getBalanceBefore(), txn.getBalanceAfter(),
                txn.getDescription(), txn.getCreatedAt()
        ), "WITHDRAWAL_" + txn.getStatus().name());
    }

    // ── TRANSFER ──────────────────────────────────────────────────
    // Sends TWO separate audit events:
    //   1. DEBIT  event with sender's   name/email → ref = TXNxxx
    //   2. CREDIT event with recipient's name/email → ref = TXNxxx-CR
    // The audit service stores each as a single record (no internal split needed).
    @Async
    public void logTransfer(Transaction txn) {
        // Fetch sender and recipient details independently
        String senderName     = safeGetName(txn.getFromAccount());
        String senderEmail    = safeGetEmail(txn.getFromAccount());
        String recipientName  = safeGetName(txn.getToAccount());
        String recipientEmail = safeGetEmail(txn.getToAccount());

        // ── Event 1: DEBIT record for sender ─────────────────────
        send(buildPayload(
                txn.getTransactionRef(),          // original ref
                "TRANSFER", txn.getStatus().name(),
                txn.getFromAccount(), txn.getToAccount(),
                txn.getInitiatedBy(), senderEmail, senderName,
                txn.getAmount(), txn.getFeeAmount(), txn.getFeeRate(), txn.getTotalDebited(),
                txn.getBalanceBefore(), txn.getBalanceAfter(),
                txn.getDescription(), txn.getCreatedAt()
        ), "TRANSFER_DEBIT_" + txn.getStatus().name());

        // ── Event 2: CREDIT record for recipient ──────────────────
        send(buildPayload(
                txn.getTransactionRef() + "-CR",  // suffixed ref — must be unique
                "TRANSFER", txn.getStatus().name(),
                txn.getToAccount(), txn.getFromAccount(), // swap: toAccount is now primary
                txn.getInitiatedBy(), recipientEmail, recipientName,
                txn.getAmount(), BigDecimal.ZERO, BigDecimal.ZERO, txn.getAmount(),
                null, null,                        // recipient balance not known here
                txn.getDescription(), txn.getCreatedAt()
        ), "TRANSFER_CREDIT_" + txn.getStatus().name());
    }

    // ── DAILY LIMIT ───────────────────────────────────────────────
    @Async
    public void logDailyLimitReached(String customerId, String accountNumber) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionRef",        "LIMIT-" + System.currentTimeMillis());
        payload.put("transactionType",       "DEPOSIT");
        payload.put("status",                "FAILED");
        payload.put("fromAccount",           accountNumber);
        payload.put("toAccount",             null);
        payload.put("initiatedByCustomerId", customerId);
        payload.put("initiatedByEmail",      "");
        payload.put("initiatedByName",       "");
        payload.put("amount",                BigDecimal.ZERO);
        payload.put("feeAmount",             BigDecimal.ZERO);
        payload.put("feeRate",               BigDecimal.ZERO);
        payload.put("totalDebited",          BigDecimal.ZERO);
        payload.put("balanceBefore",         BigDecimal.ZERO);
        payload.put("balanceAfter",          BigDecimal.ZERO);
        payload.put("description",           "Daily limit of 16 transactions reached");
        payload.put("transactionTime",       LocalDateTime.now().toString());
        send(payload, "DAILY_LIMIT_REACHED");
    }

    // ── NEW RECIPIENT LIMIT ───────────────────────────────────────
    @Async
    public void logNewRecipientLimitViolation(String customerId, String toAccount, String amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionRef",        "NRLIMIT-" + System.currentTimeMillis());
        payload.put("transactionType",       "TRANSFER");
        payload.put("status",                "FAILED");
        payload.put("fromAccount",           null);
        payload.put("toAccount",             toAccount);
        payload.put("initiatedByCustomerId", customerId);
        payload.put("initiatedByEmail",      "");
        payload.put("initiatedByName",       "");
        payload.put("amount",                new BigDecimal(amount));
        payload.put("feeAmount",             BigDecimal.ZERO);
        payload.put("feeRate",               BigDecimal.ZERO);
        payload.put("totalDebited",          BigDecimal.ZERO);
        payload.put("balanceBefore",         BigDecimal.ZERO);
        payload.put("balanceAfter",          BigDecimal.ZERO);
        payload.put("description",           "New recipient transfer limit exceeded");
        payload.put("transactionTime",       LocalDateTime.now().toString());
        send(payload, "NEW_RECIPIENT_LIMIT_EXCEEDED");
    }

    // ── Build payload matching AuditEventRequest exactly ─────────
    private Map<String, Object> buildPayload(
            String transactionRef, String transactionType, String status,
            String fromAccount, String toAccount,
            String initiatedByCustomerId, String initiatedByEmail, String initiatedByName,
            BigDecimal amount, BigDecimal feeAmount, BigDecimal feeRate,
            BigDecimal totalDebited, BigDecimal balanceBefore, BigDecimal balanceAfter,
            String description, LocalDateTime transactionTime) {

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("transactionRef",         transactionRef);
        map.put("transactionType",        transactionType);
        map.put("status",                 status);
        map.put("fromAccount",            fromAccount);
        map.put("toAccount",              toAccount);
        map.put("initiatedByCustomerId",  initiatedByCustomerId);
        map.put("initiatedByEmail",       initiatedByEmail  != null ? initiatedByEmail  : "");
        map.put("initiatedByName",        initiatedByName   != null ? initiatedByName   : "");
        map.put("amount",                 amount);
        map.put("feeAmount",              feeAmount);
        map.put("feeRate",                feeRate);
        map.put("totalDebited",           totalDebited);
        map.put("balanceBefore",          balanceBefore);
        map.put("balanceAfter",           balanceAfter);
        map.put("description",            description != null ? description : "");
        map.put("transactionTime",        transactionTime != null ? transactionTime.toString() : null);
        return map;
    }

    // ── POST to audit service ─────────────────────────────────────
    private void send(Map<String, Object> payload, String action) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-API-Key", internalApiKey);

            ResponseEntity<String> response = restTemplate.exchange(
                    auditServiceUrl + "/api/v1/audit/events",
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers),
                    String.class
            );
            log.info("Audit event sent: [INFO] {} → HTTP {}", action, response.getStatusCode());

        } catch (Exception e) {
            log.error("Failed to send audit event [INFO] {} — Reason: {}", action, e.getMessage());
        }
    }

    // ── Safe helpers — never throw, never block transaction ───────
    private String safeGetName(String accountNumber) {
        try {
            if (accountNumber == null) return "";
            Map<String, String> h = accountVerificationService.getAccountHolderDetails(accountNumber);
            return h.getOrDefault("fullName", "");
        } catch (Exception e) { return ""; }
    }

    private String safeGetEmail(String accountNumber) {
        try {
            if (accountNumber == null) return "";
            Map<String, String> h = accountVerificationService.getAccountHolderDetails(accountNumber);
            return h.getOrDefault("email", "");
        } catch (Exception e) { return ""; }
    }
}