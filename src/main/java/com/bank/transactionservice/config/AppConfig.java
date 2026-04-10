package com.bank.transactionservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Configuration
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppConfig {

    // Daily transaction count limit — no cap on amount, only count
    private int dailyTransactionLimit = 16;

    // New recipient: if recipient account was created < this many hours ago
    private int newRecipientThresholdHours = 24;

    // Max transfer amount to a new recipient (< 24 hrs old)
    private BigDecimal newRecipientTransferLimit = new BigDecimal("100000");

    // Amount threshold that separates two fee tiers
    private BigDecimal feeTierThreshold = new BigDecimal("10000");

    // Fee rate for amounts below threshold (2.5%)
    private BigDecimal feeRateBelowThreshold = new BigDecimal("2.5");

    // Fee rate for amounts at or above threshold (5%)
    private BigDecimal feeRateAboveThreshold = new BigDecimal("5.0");

    // Bank branding for notification emails
    private String bankName = "AegisCapital";
    private String fromEmail = "warimahesh478@gmail.com";

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // Needed by AuditService to serialize LocalDateTime correctly
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}