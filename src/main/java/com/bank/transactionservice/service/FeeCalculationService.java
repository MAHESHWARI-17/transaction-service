package com.bank.transactionservice.service;

import com.bank.transactionservice.config.AppConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

// Calculates transaction fees based on amount tiers:
//
//   Amount < ₹10,000  → Fee = 2.5% of amount
//   Amount >= ₹10,000 → Fee = 5.0% of amount
//
// Fee applies to:  WITHDRAWAL and TRANSFER
// Fee does NOT apply to: DEPOSIT
//
// Example:
//   Withdraw ₹5,000  → Fee = 5000 * 2.5% = ₹125.00  → Total debited = ₹5,125.00
//   Transfer ₹15,000 → Fee = 15000 * 5%  = ₹750.00  → Total debited = ₹15,750.00
@Service
@RequiredArgsConstructor
@Slf4j
public class FeeCalculationService {

    private final AppConfig appConfig;

    // Returns the fee rate (as percentage) for the given amount
    public BigDecimal getFeeRate(BigDecimal amount) {
        if (amount.compareTo(appConfig.getFeeTierThreshold()) < 0) {
            return appConfig.getFeeRateBelowThreshold(); // 2.5%
        } else {
            return appConfig.getFeeRateAboveThreshold(); // 5.0%
        }
    }

    // Returns the actual fee amount in rupees
    public BigDecimal calculateFee(BigDecimal amount) {
        BigDecimal rate = getFeeRate(amount);
        // fee = amount * rate / 100, rounded to 2 decimal places
        return amount.multiply(rate)
                     .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    // Returns the total amount to be debited from account (amount + fee)
    public BigDecimal calculateTotalDebit(BigDecimal amount) {
        return amount.add(calculateFee(amount));
    }

    // Builds a human-readable fee breakdown string for logging
    public String describeFee(BigDecimal amount) {
        BigDecimal rate = getFeeRate(amount);
        BigDecimal fee  = calculateFee(amount);
        BigDecimal total = calculateTotalDebit(amount);
        return String.format(
            "Amount: ₹%.2f | Rate: %.1f%% | Fee: ₹%.2f | Total debited: ₹%.2f",
            amount, rate, fee, total);
    }
}
