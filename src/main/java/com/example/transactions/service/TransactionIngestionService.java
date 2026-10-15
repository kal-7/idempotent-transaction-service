package com.example.transactions.service;

import com.example.transactions.api.*;
import com.example.transactions.model.*;
import com.example.transactions.repo.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransactionIngestionService {
    private final TransactionRecordRepository transactions;
    private final TransactionTemplate transactionTemplate;

    public TransactionIngestionService(TransactionRecordRepository transactions, TransactionTemplate transactionTemplate) {
        this.transactions=transactions; this.transactionTemplate=transactionTemplate;
    }

    public TransactionResponse ingest(TransactionRequest request) {
        try {
            return transactionTemplate.execute(status -> ingestWithinTransaction(request));
        } catch (DataIntegrityViolationException ex) {
            return resolveConcurrentSubmission(request);
        }
    }

    private TransactionResponse ingestWithinTransaction(TransactionRequest request) {
        var byRequest = transactions.findByRequestId(request.requestId());
        if (byRequest.isPresent()) {
            TransactionRecord existing=byRequest.get();
            if (!samePayload(existing, request)) throw new IdempotencyConflictException("requestId was already used with a different payload");
            return TransactionResponse.from(existing, "Request replay detected; existing status returned");
        }
        var byTxn = transactions.findByTransactionId(request.transactionId());
        if (byTxn.isPresent()) {
            TransactionRecord existing=byTxn.get();
            if (!samePayload(existing, request)) throw new IdempotencyConflictException("transactionId was reused with a different payload");
            // Keep the original transaction as the source of truth; do not create a second ledger effect.
            return TransactionResponse.from(existing, "Duplicate business transaction; no additional processing scheduled");
        }
        if (transactions.findByAccountIdAndStatusInOrderBySequenceNumberAsc(request.accountId(),
                java.util.List.of(TransactionStatus.RECEIVED, TransactionStatus.RETRY_PENDING, TransactionStatus.PENDING_SEQUENCE))
                .stream().anyMatch(t -> t.getCurrency().equals(request.currency())
                    && t.getSequenceNumber().equals(request.sequenceNumber()))) {
            return resolveConcurrentSubmission(request);
        }
        TransactionRecord record = new TransactionRecord(request.transactionId(), request.requestId(),
            request.accountId(), request.transactionType(), request.amount(), request.currency(),
            request.sequenceNumber(), Boolean.TRUE.equals(request.simulateTransientFailure()));
        record.setStatus(TransactionStatus.RECEIVED);
        transactions.saveAndFlush(record);
        return TransactionResponse.from(record, "Accepted for background processing");
    }

    private TransactionResponse resolveConcurrentSubmission(TransactionRequest request) {
        var existing = transactions.findByRequestId(request.requestId());
        if (existing.isEmpty()) existing = transactions.findByTransactionId(request.transactionId());
        if (existing.isPresent()) {
            TransactionRecord record=existing.get();
            if (!samePayload(record, request)) {
                throw new IdempotencyConflictException("An idempotency key was concurrently used with a different payload");
            }
            return TransactionResponse.from(record, "Duplicate business transaction; no additional processing scheduled");
        }
        throw new IdempotencyConflictException("sequenceNumber already exists for this account and currency");
    }

    private boolean samePayload(TransactionRecord a, TransactionRequest b) {
        return a.getAccountId().equals(b.accountId()) && a.getTransactionType()==b.transactionType()
            && a.getAmount().compareTo(b.amount())==0 && a.getCurrency().equals(b.currency())
            && a.getSequenceNumber().equals(b.sequenceNumber());
    }
}
