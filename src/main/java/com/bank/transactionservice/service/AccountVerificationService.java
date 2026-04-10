package com.bank.transactionservice.service;

import com.bank.transactionservice.exception.CustomExceptions.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountVerificationService {

    private final RestTemplate restTemplate;

    @Value("${account-service.url}")
    private String accountServiceUrl;

    // ── Verify PIN → returns current balance if PIN correct ───────
    public BigDecimal verifyPinAndGetBalance(String accountNumber,
                                             String pin,
                                             String jwtToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(jwtToken);

            Map<String, String> body = Map.of("accountNumber", accountNumber, "pin", pin);

            ResponseEntity<Map> response = restTemplate.exchange(
                    accountServiceUrl + "/accounts/verify-pin",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            Map<String, Object> data = extractData(response);
            return new BigDecimal(data.get("balance").toString());

        } catch (HttpClientErrorException.Unauthorized e) {
            throw new InvalidPinException("Incorrect PIN. Please try again.");
        } catch (HttpClientErrorException.Forbidden e) {
            throw new AccountNotActiveException("Account is not active. Please contact support.");
        } catch (HttpClientErrorException.NotFound e) {
            throw new AccountNotFoundException("Account not found: " + accountNumber);
        } catch (InvalidPinException | AccountNotActiveException | AccountNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("PIN verification failed for {}: {}", accountNumber, e.getMessage());
            throw new RuntimeException("Unable to verify PIN. Please try again.");
        }
    }

    // ── Get balance without PIN ───────────────────────────────────
    public BigDecimal getBalance(String accountNumber, String jwtToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(jwtToken);

            ResponseEntity<Map> response = restTemplate.exchange(
                    accountServiceUrl + "/accounts/balance/" + accountNumber,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );

            Map<String, Object> data = extractData(response);
            return new BigDecimal(data.get("balance").toString());

        } catch (HttpClientErrorException.NotFound e) {
            throw new AccountNotFoundException("Account not found: " + accountNumber);
        } catch (HttpClientErrorException.Forbidden e) {
            throw new AccountNotActiveException("Account " + accountNumber + " is not active.");
        } catch (AccountNotFoundException | AccountNotActiveException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get balance for {}: {}", accountNumber, e.getMessage());
            throw new RuntimeException("Unable to fetch account details.");
        }
    }

    // ── Get account creation time ─────────────────────────────────
    public LocalDateTime getAccountCreatedAt(String accountNumber, String jwtToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(jwtToken);

            ResponseEntity<Map> response = restTemplate.exchange(
                    accountServiceUrl + "/accounts/info/" + accountNumber,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );

            Map<String, Object> data = extractData(response);
            return LocalDateTime.parse(data.get("createdAt").toString());

        } catch (Exception e) {
            log.warn("Could not get createdAt for account {}: {}", accountNumber, e.getMessage());
            return LocalDateTime.now().minusDays(2);
        }
    }

    // ── Get account type — SAVINGS or CURRENT ────────────────────
    // No JWT needed — uses the internal permitAll endpoint
    // Returns "SAVINGS" as safe default if call fails
    public String getAccountType(String accountNumber) {
        try {
            HttpHeaders headers = new HttpHeaders();

            ResponseEntity<Map> response = restTemplate.exchange(
                    accountServiceUrl + "/accounts/info/" + accountNumber,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );

            Map<String, Object> data = extractData(response);
            return data != null
                    ? data.getOrDefault("accountType", "SAVINGS").toString()
                    : "SAVINGS";

        } catch (Exception e) {
            log.warn("Could not get account type for {}: {} — defaulting to SAVINGS",
                    accountNumber, e.getMessage());
            return "SAVINGS";  // Stricter default — apply limit if unknown
        }
    }

    // ── Get email of account holder ───────────────────────────────
    public String getAccountHolderEmail(String accountNumber, String jwtToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(jwtToken);

            ResponseEntity<Map> response = restTemplate.exchange(
                    accountServiceUrl + "/accounts/holder/" + accountNumber,
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class
            );

            Map<String, Object> data = extractData(response);
            return data != null ? (String) data.get("email") : null;
        } catch (Exception e) {
            log.warn("Could not get email for account {}: {}", accountNumber, e.getMessage());
            return null;
        }
    }

    // ── Get full name of account holder ──────────────────────────
    public String getAccountHolderName(String accountNumber, String jwtToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(jwtToken);

            ResponseEntity<Map> response = restTemplate.exchange(
                    accountServiceUrl + "/accounts/holder/" + accountNumber,
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class
            );

            Map<String, Object> data = extractData(response);
            return data != null ? (String) data.get("fullName") : null;
        } catch (Exception e) {
            log.warn("Could not get name for account {}: {}", accountNumber, e.getMessage());
            return null;
        }
    }

    // ── Get holder name + email in one call (used by AuditService) ─
    @SuppressWarnings("unchecked")
    public Map<String, String> getAccountHolderDetails(String accountNumber) {
        try {
            HttpHeaders headers = new HttpHeaders();

            ResponseEntity<Map> response = restTemplate.exchange(
                    accountServiceUrl + "/accounts/holder/" + accountNumber,
                    HttpMethod.GET, new HttpEntity<>(headers), Map.class
            );

            if (response.getBody() == null) return Map.of("fullName", "", "email", "");

            Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
            if (data == null) return Map.of("fullName", "", "email", "");

            Map<String, String> result = new HashMap<>();
            result.put("fullName", String.valueOf(data.getOrDefault("fullName", "")));
            result.put("email",    String.valueOf(data.getOrDefault("email",    "")));
            return result;

        } catch (Exception e) {
            log.warn("Could not get holder details for {}: {}", accountNumber, e.getMessage());
            return Map.of("fullName", "", "email", "");
        }
    }

    // ── Update balance after a transaction ────────────────────────
    public void updateBalance(String accountNumber, BigDecimal newBalance, String jwtToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(jwtToken);

            Map<String, Object> body = Map.of(
                    "accountNumber", accountNumber,
                    "newBalance", newBalance
            );

            restTemplate.exchange(
                    accountServiceUrl + "/accounts/balance/update",
                    HttpMethod.PUT,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            log.info("Balance updated for account {} → ₹{}", accountNumber, newBalance);

        } catch (Exception e) {
            log.error("CRITICAL: Failed to update balance for {}: {}",
                    accountNumber, e.getMessage());
            throw new RuntimeException(
                    "Failed to update account balance. Transaction rolled back.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(ResponseEntity<Map> response) {
        if (response.getBody() == null)
            throw new RuntimeException("Empty response from account service.");
        return (Map<String, Object>) response.getBody().get("data");
    }
}
