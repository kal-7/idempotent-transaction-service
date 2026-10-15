package com.example.transactions.service;

import com.example.transactions.model.*;
import com.example.transactions.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;

@Service
public class TransactionProcessor {
    private static final Logger log=LoggerFactory.getLogger(TransactionProcessor.class);
    private final TransactionRecordRepository transactions;
    private final AccountRepository accounts;
    private final LedgerEntryRepository ledger;
    private final int maxAttempts;
    private final long initialRetryDelayMs;
    private final long maxRetryDelayMs;

    public TransactionProcessor(TransactionRecordRepository transactions, AccountRepository accounts,
            LedgerEntryRepository ledger, @Value("${app.worker.max-attempts:3}") int maxAttempts,
            @Value("${app.worker.retry.initial-delay-ms:1000}") long initialRetryDelayMs,
            @Value("${app.worker.retry.max-delay-ms:60000}") long maxRetryDelayMs) {
        if (maxAttempts < 1 || initialRetryDelayMs < 0 || maxRetryDelayMs < initialRetryDelayMs) {
            throw new IllegalArgumentException("Invalid transaction retry configuration");
        }
        this.transactions=transactions; this.accounts=accounts; this.ledger=ledger; this.maxAttempts=maxAttempts;
        this.initialRetryDelayMs=initialRetryDelayMs; this.maxRetryDelayMs=maxRetryDelayMs;
    }

    @Transactional
    public void processOne(Long id) {
        TransactionRecord tx=transactions.findByIdForUpdate(id).orElseThrow();
        if (isTerminal(tx.getStatus()) || tx.getStatus()==TransactionStatus.PROCESSING) return;
        if (tx.getStatus()==TransactionStatus.RETRY_PENDING && tx.getNextAttemptAt()!=null
                && tx.getNextAttemptAt().isAfter(Instant.now())) return;
        var accountOpt=accounts.findByAccountIdAndCurrencyForUpdate(tx.getAccountId(), tx.getCurrency());
        Account account=accountOpt.orElseGet(() -> accounts.saveAndFlush(new Account(tx.getAccountId(), tx.getCurrency())));

        long expected=account.getLastProcessedSequence()+1;
        if (tx.getSequenceNumber()>expected) {
            tx.clearNextAttemptAt();
            tx.setStatus(TransactionStatus.PENDING_SEQUENCE);
            tx.setLastError("Waiting for sequence " + expected);
            return;
        }
        if (tx.getSequenceNumber()<expected) {
            // A different transaction has already advanced this sequence; never apply this effect.
            tx.clearNextAttemptAt();
            tx.setStatus(TransactionStatus.FAILED);
            tx.setLastError("Stale sequence; expected " + expected);
            log.warn("Stale transaction rejected transactionId={}", tx.getTransactionId());
            return;
        }

        tx.setStatus(TransactionStatus.PROCESSING);
        tx.incrementAttempts();

        // Deliberate assessment hook: fail only once, before changing balance or ledger.
        if (tx.shouldSimulateTransientFailure() && !tx.isTransientFailureTriggered()) {
            tx.markTransientFailureTriggered();
            scheduleRetryOrExhaust(tx, "Simulated transient failure");
            log.warn("Transient failure transactionId={} attempt={}", tx.getTransactionId(), tx.getAttempts());
            return;
        }

        BigDecimal delta = tx.getTransactionType()==TransactionType.CREDIT ? tx.getAmount() : tx.getAmount().negate();
        if (account.getBalance().add(delta).compareTo(BigDecimal.ZERO)<0) {
            tx.clearNextAttemptAt();
            tx.setLastError("Insufficient funds");
            tx.setStatus(TransactionStatus.FAILED);
            log.warn("Business validation failed transactionId={}", tx.getTransactionId());
            return;
        }
        // All three writes occur in one DB transaction. Unique ledger transactionId is a final guard.
        if (!ledger.existsByTransactionId(tx.getTransactionId())) {
            account.apply(delta);
            account.setLastProcessedSequence(tx.getSequenceNumber());
            ledger.save(new LedgerEntry(tx.getTransactionId(), tx.getAccountId(), delta, tx.getCurrency()));
        }
        tx.setLastError(null);
        tx.clearNextAttemptAt();
        tx.setStatus(TransactionStatus.PROCESSED);
        log.info("Transaction processed transactionId={} accountId={} sequence={} attempt={}",
            tx.getTransactionId(), tx.getAccountId(), tx.getSequenceNumber(), tx.getAttempts());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordProcessingFailure(Long id, String errorType) {
        TransactionRecord tx=transactions.findByIdForUpdate(id).orElseThrow();
        if (isTerminal(tx.getStatus())) return;
        tx.incrementAttempts();
        scheduleRetryOrExhaust(tx, "Processing error: " + errorType);
        if (tx.getStatus()==TransactionStatus.RETRY_EXHAUSTED) {
            log.error("Retries exhausted transactionId={} attempts={} errorType={}",
                tx.getTransactionId(), tx.getAttempts(), errorType);
        } else {
            log.warn("Transaction scheduled for retry transactionId={} attempt={} nextAttemptAt={} errorType={}",
                tx.getTransactionId(), tx.getAttempts(), tx.getNextAttemptAt(), errorType);
        }
    }

    private void scheduleRetryOrExhaust(TransactionRecord tx, String error) {
        if (tx.getAttempts() >= maxAttempts) {
            tx.markRetryExhausted(error);
            return;
        }
        long delay=initialRetryDelayMs;
        for (int i=1; i<tx.getAttempts() && delay<maxRetryDelayMs; i++) {
            delay=delay > maxRetryDelayMs / 2 ? maxRetryDelayMs : delay * 2;
        }
        tx.scheduleRetry(error, Instant.now().plusMillis(Math.min(delay, maxRetryDelayMs)));
    }

    private boolean isTerminal(TransactionStatus status) {
        return status==TransactionStatus.PROCESSED || status==TransactionStatus.FAILED
            || status==TransactionStatus.RETRY_EXHAUSTED;
    }
}
