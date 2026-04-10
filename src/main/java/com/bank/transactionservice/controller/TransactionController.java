package com.bank.transactionservice.controller;

import com.bank.transactionservice.dto.request.*;
import com.bank.transactionservice.dto.response.*;
import com.bank.transactionservice.service.AccountVerificationService;
import com.bank.transactionservice.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final AccountVerificationService accountVerificationService;

    // ── POST /transactions/deposit ────────────────────────────────
    @PostMapping("/deposit")
    public ResponseEntity<ApiResponse<TransactionResponse>> deposit(
            @Valid @RequestBody DepositRequest request,
            @AuthenticationPrincipal String customerId,
            HttpServletRequest httpRequest) {

        TransactionResponse response = transactionService.deposit(
                request, customerId, extractToken(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Deposit successful.", response));
    }

    // ── POST /transactions/withdraw ───────────────────────────────
    @PostMapping("/withdraw")
    public ResponseEntity<ApiResponse<TransactionResponse>> withdraw(
            @Valid @RequestBody WithdrawRequest request,
            @AuthenticationPrincipal String customerId,
            HttpServletRequest httpRequest) {

        TransactionResponse response = transactionService.withdraw(
                request, customerId, extractToken(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("Withdrawal successful.", response));
    }

    // ── POST /transactions/transfer ───────────────────────────────
    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<TransactionResponse>> transfer(
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal String customerId,
            HttpServletRequest httpRequest) {

        TransactionResponse response = transactionService.transfer(
                request, customerId, extractToken(httpRequest));
        return ResponseEntity.ok(ApiResponse.success("Transfer successful.", response));
    }

    // ── GET /transactions/history/{accountNumber} ─────────────────
    @GetMapping("/history/{accountNumber}")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getHistory(
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Page<TransactionResponse> history =
                transactionService.getHistory(accountNumber, page, size);
        return ResponseEntity.ok(ApiResponse.success(
                "Transaction history fetched successfully.", history));
    }

    // ── GET /transactions/statement/{accountNumber} ───────────────
    @GetMapping("/statement/{accountNumber}")
    public ResponseEntity<ApiResponse<List<TransactionResponse>>> getStatement(
            @PathVariable String accountNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        List<TransactionResponse> statement =
                transactionService.getStatement(accountNumber, from, to);
        return ResponseEntity.ok(ApiResponse.success(
                "Statement fetched for " + from + " to " + to + ".", statement));
    }

    // ── GET /transactions/{ref} ───────────────────────────────────
    @GetMapping("/{transactionRef}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getByRef(
            @PathVariable String transactionRef) {

        TransactionResponse txn = transactionService.getByRef(transactionRef);
        return ResponseEntity.ok(ApiResponse.success("Transaction found.", txn));
    }

    // ── GET /transactions/remaining/{accountNumber} ───────────────
    // Returns remaining daily transactions for SAVINGS accounts.
    // For CURRENT accounts: remainingToday = -1, unlimited = true, dailyLimit = null
    @GetMapping("/remaining/{accountNumber}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRemaining(
            @PathVariable String accountNumber) {

        int remaining = transactionService.getRemainingTransactions(accountNumber);
        String accountType = accountVerificationService.getAccountType(accountNumber);
        boolean isUnlimited = "CURRENT".equalsIgnoreCase(accountType);

        Map<String, Object> data = new HashMap<>();
        data.put("accountNumber",  accountNumber);
        data.put("accountType",    accountType);
        data.put("unlimited",      isUnlimited);
        data.put("remainingToday", isUnlimited ? null : remaining);
        data.put("dailyLimit",     isUnlimited ? null : 16);
        data.put("used",           isUnlimited ? null : (16 - remaining));

        return ResponseEntity.ok(ApiResponse.success(
                isUnlimited
                        ? "Current account — no daily transaction limit."
                        : "Remaining transactions today.",
                data));
    }

    // ── Helper ────────────────────────────────────────────────────
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer "))
            return header.substring(7);
        return "";
    }
}
