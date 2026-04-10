package com.bank.transactionservice.service;

import com.bank.transactionservice.dto.request.*;
import com.bank.transactionservice.dto.response.TransactionResponse;
import com.bank.transactionservice.entity.Transaction;
import com.bank.transactionservice.entity.Transaction.TransactionStatus;
import com.bank.transactionservice.entity.Transaction.TransactionType;
import com.bank.transactionservice.exception.CustomExceptions.*;
import com.bank.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository       transactionRepository;
    private final AccountVerificationService  accountVerificationService;
    private final FeeCalculationService       feeCalculationService;
    private final TransactionLimitService     limitService;
    private final AuditService               auditService;
    private final TransactionEmailService    emailService;

    @Transactional
    public TransactionResponse deposit(DepositRequest request, String customerId, String jwtToken) {
        limitService.checkDailyTransactionCount(request.getAccountNumber());
        BigDecimal currentBalance = accountVerificationService.getBalance(request.getAccountNumber(), jwtToken);
        BigDecimal newBalance = currentBalance.add(request.getAmount());
        accountVerificationService.updateBalance(request.getAccountNumber(), newBalance, jwtToken);

        Transaction txn = Transaction.builder()
                .transactionRef(generateRef())
                .toAccount(request.getAccountNumber())
                .transactionType(TransactionType.DEPOSIT)
                .amount(request.getAmount())
                .feeAmount(BigDecimal.ZERO)
                .feeRate(BigDecimal.ZERO)
                .totalDebited(request.getAmount())
                .balanceBefore(currentBalance)
                .balanceAfter(newBalance)
                .status(TransactionStatus.SUCCESS)
                .description(request.getDescription() != null ? request.getDescription() : "Cash Deposit")
                .initiatedBy(customerId)
                .build();
        transactionRepository.save(txn);
        limitService.incrementDailyCount(request.getAccountNumber());

        // async: audit log + email notification
        auditService.logDeposit(txn);
        emailService.sendDepositNotification(txn, jwtToken);

        log.info("DEPOSIT ₹{} → {} | Ref: {} | New balance: ₹{}",
                request.getAmount(), request.getAccountNumber(), txn.getTransactionRef(), newBalance);
        return TransactionResponse.from(txn);
    }

    @Transactional
    public TransactionResponse withdraw(WithdrawRequest request, String customerId, String jwtToken) {
        limitService.checkDailyTransactionCount(request.getAccountNumber());
        BigDecimal currentBalance = accountVerificationService
                .verifyPinAndGetBalance(request.getAccountNumber(), request.getPin(), jwtToken);

        BigDecimal feeRate    = feeCalculationService.getFeeRate(request.getAmount());
        BigDecimal feeAmount  = feeCalculationService.calculateFee(request.getAmount());
        BigDecimal totalDebit = request.getAmount().add(feeAmount);

        if (currentBalance.compareTo(totalDebit) < 0) {
            throw new InsufficientBalanceException(String.format(
                    "Insufficient balance. Need ₹%.2f (₹%.2f + ₹%.2f fee @ %.1f%%). Available: ₹%.2f",
                    totalDebit, request.getAmount(), feeAmount, feeRate, currentBalance));
        }

        BigDecimal newBalance = currentBalance.subtract(totalDebit);
        accountVerificationService.updateBalance(request.getAccountNumber(), newBalance, jwtToken);

        Transaction txn = Transaction.builder()
                .transactionRef(generateRef())
                .fromAccount(request.getAccountNumber())
                .transactionType(TransactionType.WITHDRAWAL)
                .amount(request.getAmount())
                .feeAmount(feeAmount)
                .feeRate(feeRate)
                .totalDebited(totalDebit)
                .balanceBefore(currentBalance)
                .balanceAfter(newBalance)
                .status(TransactionStatus.SUCCESS)
                .description(request.getDescription() != null ? request.getDescription() : "Cash Withdrawal")
                .initiatedBy(customerId)
                .build();
        transactionRepository.save(txn);
        limitService.incrementDailyCount(request.getAccountNumber());

        auditService.logWithdrawal(txn);
        emailService.sendWithdrawalNotification(txn, jwtToken);

        log.info("WITHDRAWAL ₹{} from {} | Fee: ₹{} | Total: ₹{} | Ref: {} | Balance: ₹{}",
                request.getAmount(), request.getAccountNumber(),
                feeAmount, totalDebit, txn.getTransactionRef(), newBalance);
        return TransactionResponse.from(txn);
    }

    @Transactional
    public TransactionResponse transfer(TransferRequest request, String customerId, String jwtToken) {
        if (request.getFromAccount().equals(request.getToAccount()))
            throw new SameAccountTransferException("Cannot transfer to the same account.");

        limitService.checkDailyTransactionCount(request.getFromAccount());
        BigDecimal fromBalance = accountVerificationService
                .verifyPinAndGetBalance(request.getFromAccount(), request.getPin(), jwtToken);

        BigDecimal feeRate    = feeCalculationService.getFeeRate(request.getAmount());
        BigDecimal feeAmount  = feeCalculationService.calculateFee(request.getAmount());
        BigDecimal totalDebit = request.getAmount().add(feeAmount);

        if (fromBalance.compareTo(totalDebit) < 0) {
            throw new InsufficientBalanceException(String.format(
                    "Insufficient balance. Need ₹%.2f (₹%.2f + ₹%.2f fee @ %.1f%%). Available: ₹%.2f",
                    totalDebit, request.getAmount(), feeAmount, feeRate, fromBalance));
        }

        BigDecimal toBalance = accountVerificationService.getBalance(request.getToAccount(), jwtToken);
        LocalDateTime recipientCreatedAt = accountVerificationService
                .getAccountCreatedAt(request.getToAccount(), jwtToken);
        boolean isNewRecipient = limitService.isNewRecipient(recipientCreatedAt);
        limitService.checkNewRecipientLimit(recipientCreatedAt, request.getAmount());

        BigDecimal newFromBalance = fromBalance.subtract(totalDebit);
        BigDecimal newToBalance   = toBalance.add(request.getAmount());

        accountVerificationService.updateBalance(request.getFromAccount(), newFromBalance, jwtToken);
        accountVerificationService.updateBalance(request.getToAccount(), newToBalance, jwtToken);

        Transaction txn = Transaction.builder()
                .transactionRef(generateRef())
                .fromAccount(request.getFromAccount())
                .toAccount(request.getToAccount())
                .transactionType(TransactionType.TRANSFER)
                .amount(request.getAmount())
                .feeAmount(feeAmount)
                .feeRate(feeRate)
                .totalDebited(totalDebit)
                .balanceBefore(fromBalance)
                .balanceAfter(newFromBalance)
                .status(TransactionStatus.SUCCESS)
                .description(request.getDescription() != null ? request.getDescription() : "Fund Transfer")
                .initiatedBy(customerId)
                .newRecipientFlag(isNewRecipient)
                .build();
        transactionRepository.save(txn);
        limitService.incrementDailyCount(request.getFromAccount());

        auditService.logTransfer(txn);
        emailService.sendTransferNotification(txn, jwtToken);

        log.info("TRANSFER ₹{} from {} → {} | Fee: ₹{} | Ref: {} | From balance: ₹{} | New recipient: {}",
                request.getAmount(), request.getFromAccount(), request.getToAccount(),
                feeAmount, txn.getTransactionRef(), newFromBalance, isNewRecipient);
        return TransactionResponse.from(txn);
    }

    public Page<TransactionResponse> getHistory(String accountNumber, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return transactionRepository.findByAccount(accountNumber, pageable).map(TransactionResponse::from);
    }

    public List<TransactionResponse> getStatement(String accountNumber, LocalDate from, LocalDate to) {
        return transactionRepository.findByAccountAndDateRange(
                        accountNumber, from.atStartOfDay(), to.atTime(23, 59, 59))
                .stream().map(TransactionResponse::from).toList();
    }

    public TransactionResponse getByRef(String transactionRef) {
        Transaction txn = transactionRepository.findByTransactionRef(transactionRef)
                .orElseThrow(() -> new RuntimeException("Transaction not found: " + transactionRef));
        return TransactionResponse.from(txn);
    }

    public int getRemainingTransactions(String accountNumber) {
        return limitService.getRemainingTransactions(accountNumber);
    }

    private String generateRef() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = LocalDate.now().atTime(23, 59, 59);
        long seq = transactionRepository.countTodayTransactions(startOfDay, endOfDay) + 1;
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return "TXN" + date + String.format("%04d", seq);
    }
}
