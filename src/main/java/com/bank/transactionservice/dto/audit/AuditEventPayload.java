package com.bank.transactionservice.dto.audit;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.time.LocalDateTime;

// DTO that matches AuditEventRequest in the audit-service exactly.
// Transaction Service builds this and POSTs it to audit-service.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventPayload {

    private String        serviceName;   // always "TRANSACTIONSERVICE"
    private String        action;        // e.g. DEPOSIT_SUCCESS, TRANSFER_FAILED
    private String        severity;      // INFO | WARN | ERROR
    private String        actorUserId;   // customer_id who initiated
    private String        actorRole;     // always "CUSTOMER"
    private String        entityType;    // always "TRANSACTION"
    private String        entityId;      // transaction UUID (as string)
    private JsonNode      metadata;      // full transaction details
    private LocalDateTime occurredAt;    // transaction timestamp
}