package com.bank.transactionservice.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class DepositRequest {

    @NotBlank(message = "Account number is required")
    private String accountNumber;

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1.00", message = "Minimum deposit amount is ₹1")
    @DecimalMax(value = "10000000.00", message = "Maximum deposit amount is ₹1,00,00,000 per transaction")
    private BigDecimal amount;

    // Optional note e.g. "Salary", "Refund"
    private String description;
}
