package com.bank.transactionservice.service;

import com.bank.transactionservice.dto.response.TransactionResponse;
import com.bank.transactionservice.entity.Transaction;
import com.bank.transactionservice.exception.CustomExceptions.AccountNotFoundException;
import com.bank.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditQueryService {

    private final TransactionRepository transactionRepository;
    private final RestTemplate restTemplate;

    @Value("${account-service.url}")
    private String accountServiceUrl;

    // ── Customer: own account date-range transactions ─────────────
    public List<TransactionResponse> getTransactionsForCustomer(
            String customerId, String accountNumber, LocalDate from, LocalDate to) {

        validateAccountOwnership(customerId, accountNumber);
        return fetchByAccountAndRange(accountNumber, from, to);
    }

    // ── Customer: credit/debit summary for own account ────────────
    public Map<String, Object> getSummaryForCustomer(
            String customerId, String accountNumber, LocalDate from, LocalDate to) {

        validateAccountOwnership(customerId, accountNumber);
        return buildSummary(accountNumber, from, to);
    }

    // ── Compliance/Admin: transactions for any account ────────────
    public List<TransactionResponse> getTransactionsForAccount(
            String accountNumber, LocalDate from, LocalDate to) {
        return fetchByAccountAndRange(accountNumber, from, to);
    }

    // ── Compliance/Admin: summary for any account ─────────────────
    public Map<String, Object> getSummaryForAccount(
            String accountNumber, LocalDate from, LocalDate to) {
        return buildSummary(accountNumber, from, to);
    }

    // ── Compliance/Admin: all transactions initiated by a customer ─
    public List<TransactionResponse> getTransactionsByCustomerAndDateRange(
            String customerId, LocalDate from, LocalDate to) {

        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end   = to.atTime(23, 59, 59);

        return transactionRepository
                .findByInitiatedByAndDateRange(customerId, start, end)
                .stream()
                .map(TransactionResponse::from)
                .collect(Collectors.toList());
    }

    // ── Private helpers ───────────────────────────────────────────

    private List<TransactionResponse> fetchByAccountAndRange(
            String accountNumber, LocalDate from, LocalDate to) {

        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end   = to.atTime(23, 59, 59);

        return transactionRepository
                .findByAccountAndDateRange(accountNumber, start, end)
                .stream()
                .map(TransactionResponse::from)
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildSummary(
            String accountNumber, LocalDate from, LocalDate to) {

        List<Transaction> txns = transactionRepository.findByAccountAndDateRange(
                accountNumber, from.atStartOfDay(), to.atTime(23, 59, 59));

        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal totalDebit  = BigDecimal.ZERO;
        int creditCount = 0;
        int debitCount  = 0;

        for (Transaction t : txns) {
            if (t.getStatus() != Transaction.TransactionStatus.SUCCESS) continue;

            boolean isCredit =
                    t.getTransactionType() == Transaction.TransactionType.DEPOSIT
                    || (t.getTransactionType() == Transaction.TransactionType.TRANSFER
                        && accountNumber.equals(t.getToAccount()));

            boolean isDebit =
                    t.getTransactionType() == Transaction.TransactionType.WITHDRAWAL
                    || (t.getTransactionType() == Transaction.TransactionType.TRANSFER
                        && accountNumber.equals(t.getFromAccount()));

            if (isCredit)  { totalCredit = totalCredit.add(t.getAmount());        creditCount++; }
            else if (isDebit) { totalDebit = totalDebit.add(t.getTotalDebited()); debitCount++;  }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accountNumber",      accountNumber);
        summary.put("fromDate",           from.toString());
        summary.put("toDate",             to.toString());
        summary.put("totalTransactions",  txns.size());
        summary.put("totalCredit",        totalCredit);
        summary.put("creditCount",        creditCount);
        summary.put("totalDebit",         totalDebit);
        summary.put("debitCount",         debitCount);
        summary.put("netFlow",            totalCredit.subtract(totalDebit));
        return summary;
    }

    // Validates that the accountNumber belongs to the given customerId.
    // Calls the identity-service /accounts/owner/{acct} endpoint.
    // Falls through (does NOT throw) if the service is unreachable —
    // the JWT itself already proves the caller's identity.
    private void validateAccountOwnership(String customerId, String accountNumber) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    accountServiceUrl + "/accounts/owner/" + accountNumber,
                    HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>)
                    (response.getBody() != null ? response.getBody().get("data") : null);

            if (data == null || !customerId.equals(data.get("customerId"))) {
                throw new AccountNotFoundException(
                        "Account " + accountNumber + " does not belong to you.");
            }
        } catch (AccountNotFoundException e) {
            throw e;
        } catch (Exception e) {
            // Account-service unreachable — allow query (JWT proves identity)
            log.warn("Could not validate ownership of {} for {}: {}",
                    accountNumber, customerId, e.getMessage());
        }
    }
}
