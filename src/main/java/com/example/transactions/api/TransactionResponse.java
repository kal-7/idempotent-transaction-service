package com.example.transactions.api;

import com.example.transactions.model.TransactionRecord;
import com.example.transactions.model.TransactionStatus;
import java.time.Instant;

public record TransactionResponse(String transactionId, String requestId, String accountId,
        Long sequenceNumber, TransactionStatus status, int attempts, Instant nextAttemptAt, String message,
        String lastError, Instant createdAt, Instant updatedAt) {
    public static TransactionResponse from(TransactionRecord r, String message) {
        return new TransactionResponse(r.getTransactionId(), r.getRequestId(), r.getAccountId(),
            r.getSequenceNumber(), r.getStatus(), r.getAttempts(), r.getNextAttemptAt(), message, r.getLastError(),
            r.getCreatedAt(), r.getUpdatedAt());
    }
}
