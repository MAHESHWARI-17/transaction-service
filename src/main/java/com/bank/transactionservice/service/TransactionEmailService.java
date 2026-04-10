package com.bank.transactionservice.service;

import com.bank.transactionservice.config.AppConfig;
import com.bank.transactionservice.entity.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

import java.math.BigDecimal;

/**
 * Sends transaction notification emails to users via AWS SES.
 * Every DEPOSIT, WITHDRAWAL and TRANSFER triggers an async email.
 * The email shows: transaction type, amount, fee, remaining balance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionEmailService {

    private final SesClient sesClient;
    private final AppConfig appConfig;
    private final AccountVerificationService accountVerificationService;

    // ── DEPOSIT notification ──────────────────────────────────────
    // Recipient's email is fetched from identity service.
    @Async
    public void sendDepositNotification(Transaction txn, String jwtToken) {
        try {
            String email = accountVerificationService.getAccountHolderEmail(
                    txn.getToAccount(), jwtToken);
            String name  = accountVerificationService.getAccountHolderName(
                    txn.getToAccount(), jwtToken);
            if (email == null) return;

            String subject = appConfig.getBankName() + " — Money Credited to Your Account";
            String body = buildCreditEmail(name, txn);
            send(email, subject, body);
        } catch (Exception e) {
            log.warn("Deposit email skipped for {}: {}", txn.getToAccount(), e.getMessage());
        }
    }

    // ── WITHDRAWAL notification ───────────────────────────────────
    @Async
    public void sendWithdrawalNotification(Transaction txn, String jwtToken) {
        try {
            String email = accountVerificationService.getAccountHolderEmail(
                    txn.getFromAccount(), jwtToken);
            String name  = accountVerificationService.getAccountHolderName(
                    txn.getFromAccount(), jwtToken);
            if (email == null) return;

            String subject = appConfig.getBankName() + " — Withdrawal Alert";
            String body = buildDebitEmail(name, txn, "Withdrawal");
            send(email, subject, body);
        } catch (Exception e) {
            log.warn("Withdrawal email skipped for {}: {}", txn.getFromAccount(), e.getMessage());
        }
    }

    // ── TRANSFER: sender gets debit email, receiver gets credit email ──
    @Async
    public void sendTransferNotification(Transaction txn, String jwtToken) {
        try {
            // Debit email to sender
            String senderEmail = accountVerificationService.getAccountHolderEmail(
                    txn.getFromAccount(), jwtToken);
            String senderName  = accountVerificationService.getAccountHolderName(
                    txn.getFromAccount(), jwtToken);
            if (senderEmail != null) {
                String subject = appConfig.getBankName() + " — Fund Transfer Debit Alert";
                String body = buildTransferDebitEmail(senderName, txn);
                send(senderEmail, subject, body);
            }

            // Credit email to recipient
            String recipientEmail = accountVerificationService.getAccountHolderEmail(
                    txn.getToAccount(), jwtToken);
            String recipientName  = accountVerificationService.getAccountHolderName(
                    txn.getToAccount(), jwtToken);
            if (recipientEmail != null) {
                String subject = appConfig.getBankName() + " — Money Received";
                String body = buildTransferCreditEmail(recipientName, txn);
                send(recipientEmail, subject, body);
            }
        } catch (Exception e) {
            log.warn("Transfer email skipped for ref {}: {}", txn.getTransactionRef(), e.getMessage());
        }
    }

    // ── HTML email builders ───────────────────────────────────────

    private String buildCreditEmail(String name, Transaction txn) {
        return """
            <div style="font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:24px;
                        border:1px solid #e5e7eb;border-radius:8px">
              <h2 style="color:#059669">%s — Amount Credited ✓</h2>
              <p>Dear <strong>%s</strong>,</p>
              <p>Your account has been credited.</p>
              <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                <tr style="background:#ecfdf5">
                  <td style="padding:10px 12px;border:1px solid #a7f3d0;font-weight:bold">Transaction Ref</td>
                  <td style="padding:10px 12px;border:1px solid #a7f3d0;font-family:monospace">%s</td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #d1fae5;font-weight:bold">Account</td>
                  <td style="padding:10px 12px;border:1px solid #d1fae5">%s</td>
                </tr>
                <tr style="background:#ecfdf5">
                  <td style="padding:10px 12px;border:1px solid #a7f3d0;font-weight:bold">Amount Credited</td>
                  <td style="padding:10px 12px;border:1px solid #a7f3d0;color:#059669;font-size:18px;font-weight:bold">
                    ₹%s
                  </td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #d1fae5;font-weight:bold">Remaining Balance</td>
                  <td style="padding:10px 12px;border:1px solid #d1fae5;font-weight:bold">₹%s</td>
                </tr>
                <tr style="background:#ecfdf5">
                  <td style="padding:10px 12px;border:1px solid #a7f3d0;font-weight:bold">Description</td>
                  <td style="padding:10px 12px;border:1px solid #a7f3d0">%s</td>
                </tr>
              </table>
              <p style="color:#6b7280;font-size:13px">
                If you did not expect this transaction, please contact support immediately.
              </p>
            </div>
            """.formatted(
                appConfig.getBankName(), name,
                txn.getTransactionRef(),
                txn.getToAccount(),
                fmt(txn.getAmount()),
                fmt(txn.getBalanceAfter()),
                txn.getDescription() != null ? txn.getDescription() : "Cash Deposit"
        );
    }

    private String buildDebitEmail(String name, Transaction txn, String txnType) {
        return """
            <div style="font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:24px;
                        border:1px solid #e5e7eb;border-radius:8px">
              <h2 style="color:#dc2626">%s — %s Alert</h2>
              <p>Dear <strong>%s</strong>,</p>
              <p>A debit has been made from your account.</p>
              <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                <tr style="background:#fef2f2">
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">Transaction Ref</td>
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-family:monospace">%s</td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;font-weight:bold">Account</td>
                  <td style="padding:10px 12px;border:1px solid #fee2e2">%s</td>
                </tr>
                <tr style="background:#fef2f2">
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">Amount</td>
                  <td style="padding:10px 12px;border:1px solid #fca5a5;color:#dc2626;font-size:18px;font-weight:bold">
                    ₹%s
                  </td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;font-weight:bold">Fee Charged</td>
                  <td style="padding:10px 12px;border:1px solid #fee2e2">₹%s (%.1f%%)</td>
                </tr>
                <tr style="background:#fef2f2">
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">Total Debited</td>
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">₹%s</td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;font-weight:bold">Remaining Balance</td>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;font-weight:bold">₹%s</td>
                </tr>
                <tr style="background:#fef2f2">
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">Description</td>
                  <td style="padding:10px 12px;border:1px solid #fca5a5">%s</td>
                </tr>
              </table>
              <p style="color:#6b7280;font-size:13px">
                If you did not authorize this transaction, please contact support immediately.
              </p>
            </div>
            """.formatted(
                appConfig.getBankName(), txnType, name,
                txn.getTransactionRef(),
                txn.getFromAccount(),
                fmt(txn.getAmount()),
                fmt(txn.getFeeAmount()),
                txn.getFeeRate().doubleValue(),
                fmt(txn.getTotalDebited()),
                fmt(txn.getBalanceAfter()),
                txn.getDescription() != null ? txn.getDescription() : txnType
        );
    }

    private String buildTransferDebitEmail(String name, Transaction txn) {
        return """
            <div style="font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:24px;
                        border:1px solid #e5e7eb;border-radius:8px">
              <h2 style="color:#dc2626">%s — Fund Transfer Debit</h2>
              <p>Dear <strong>%s</strong>,</p>
              <p>A fund transfer has been debited from your account.</p>
              <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                <tr style="background:#fef2f2">
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">Transaction Ref</td>
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-family:monospace">%s</td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;font-weight:bold">From Account</td>
                  <td style="padding:10px 12px;border:1px solid #fee2e2">%s</td>
                </tr>
                <tr style="background:#fef2f2">
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">To Account</td>
                  <td style="padding:10px 12px;border:1px solid #fca5a5">%s</td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;font-weight:bold">Transfer Amount</td>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;color:#dc2626;font-size:18px;font-weight:bold">
                    ₹%s
                  </td>
                </tr>
                <tr style="background:#fef2f2">
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">Fee</td>
                  <td style="padding:10px 12px;border:1px solid #fca5a5">₹%s (%.1f%%)</td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;font-weight:bold">Total Debited</td>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;font-weight:bold">₹%s</td>
                </tr>
                <tr style="background:#fef2f2">
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">Remaining Balance</td>
                  <td style="padding:10px 12px;border:1px solid #fca5a5;font-weight:bold">₹%s</td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #fee2e2;font-weight:bold">Description</td>
                  <td style="padding:10px 12px;border:1px solid #fee2e2">%s</td>
                </tr>
              </table>
            </div>
            """.formatted(
                appConfig.getBankName(), name,
                txn.getTransactionRef(),
                txn.getFromAccount(),
                txn.getToAccount(),
                fmt(txn.getAmount()),
                fmt(txn.getFeeAmount()),
                txn.getFeeRate().doubleValue(),
                fmt(txn.getTotalDebited()),
                fmt(txn.getBalanceAfter()),
                txn.getDescription() != null ? txn.getDescription() : "Fund Transfer"
        );
    }

    private String buildTransferCreditEmail(String name, Transaction txn) {
        return """
            <div style="font-family:Arial,sans-serif;max-width:600px;margin:auto;padding:24px;
                        border:1px solid #e5e7eb;border-radius:8px">
              <h2 style="color:#059669">%s — Money Received ✓</h2>
              <p>Dear <strong>%s</strong>,</p>
              <p>Money has been transferred to your account.</p>
              <table style="width:100%%;border-collapse:collapse;margin:20px 0">
                <tr style="background:#ecfdf5">
                  <td style="padding:10px 12px;border:1px solid #a7f3d0;font-weight:bold">Transaction Ref</td>
                  <td style="padding:10px 12px;border:1px solid #a7f3d0;font-family:monospace">%s</td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #d1fae5;font-weight:bold">To Account</td>
                  <td style="padding:10px 12px;border:1px solid #d1fae5">%s</td>
                </tr>
                <tr style="background:#ecfdf5">
                  <td style="padding:10px 12px;border:1px solid #a7f3d0;font-weight:bold">Amount Received</td>
                  <td style="padding:10px 12px;border:1px solid #a7f3d0;color:#059669;font-size:18px;font-weight:bold">
                    ₹%s
                  </td>
                </tr>
                <tr>
                  <td style="padding:10px 12px;border:1px solid #d1fae5;font-weight:bold">Description</td>
                  <td style="padding:10px 12px;border:1px solid #d1fae5">%s</td>
                </tr>
              </table>
              <p style="color:#6b7280;font-size:13px">
                Note: The sender's account balance is updated accordingly.
              </p>
            </div>
            """.formatted(
                appConfig.getBankName(), name,
                txn.getTransactionRef(),
                txn.getToAccount(),
                fmt(txn.getAmount()),
                txn.getDescription() != null ? txn.getDescription() : "Fund Transfer"
        );
    }

    // ── Internal SES send ─────────────────────────────────────────
    private void send(String to, String subject, String htmlBody) {
        try {
            SendEmailRequest request = SendEmailRequest.builder()
                    .source(appConfig.getFromEmail())
                    .destination(Destination.builder().toAddresses(to).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(Body.builder()
                                    .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                                    .build())
                            .build())
                    .build();

            SendEmailResponse response = sesClient.sendEmail(request);
            log.info("Transaction email sent to {} | MessageId: {}", to, response.messageId());
        } catch (SesException e) {
            log.error("AWS SES error sending to {}: {}", to, e.awsErrorDetails().errorMessage());
        } catch (Exception e) {
            log.error("Email send failed to {}: {}", to, e.getMessage());
        }
    }

    private String fmt(BigDecimal val) {
        return val != null ? String.format("%.2f", val) : "0.00";
    }
}
