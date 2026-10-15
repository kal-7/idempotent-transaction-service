package com.example.transactions.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transaction_records", uniqueConstraints = {
    @UniqueConstraint(name = "uk_transaction_business_id", columnNames = "transaction_id"),
    @UniqueConstraint(name = "uk_transaction_request_id", columnNames = "request_id"),
    @UniqueConstraint(name = "uk_account_currency_sequence", columnNames = {"account_id", "currency", "sequence_number"})
})
public class TransactionRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="transaction_id", nullable=false, updatable=false) private String transactionId;
    @Column(name="request_id", nullable=false, updatable=false) private String requestId;
    @Column(name="account_id", nullable=false, updatable=false) private String accountId;
    @Enumerated(EnumType.STRING) @Column(nullable=false, updatable=false) private TransactionType transactionType;
    @Column(nullable=false, updatable=false, precision=19, scale=2) private BigDecimal amount;
    @Column(nullable=false, updatable=false, length=3) private String currency;
    @Column(name="sequence_number", nullable=false, updatable=false) private Long sequenceNumber;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private TransactionStatus status;
    @Column(nullable=false) private int attempts;
    @Column(length=500) private String lastError;
    private Instant nextAttemptAt;
    @Column(nullable=false, updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    @Column(nullable=false, updatable=false) private boolean simulateTransientFailure;
    @Column(nullable=false) private boolean transientFailureTriggered;

    protected TransactionRecord() {}
    public TransactionRecord(String transactionId, String requestId, String accountId, TransactionType type,
            BigDecimal amount, String currency, Long sequenceNumber, boolean simulateTransientFailure) {
        this.transactionId=transactionId; this.requestId=requestId; this.accountId=accountId;
        this.transactionType=type; this.amount=amount; this.currency=currency; this.sequenceNumber=sequenceNumber;
        this.simulateTransientFailure=simulateTransientFailure; this.status=TransactionStatus.RECEIVED;
        this.attempts=0; this.createdAt=Instant.now(); this.updatedAt=this.createdAt;
    }
    public void setStatus(TransactionStatus status) { this.status=status; this.updatedAt=Instant.now(); }
    public void incrementAttempts() { this.attempts++; this.updatedAt=Instant.now(); }
    public void setLastError(String error) { this.lastError=error; this.updatedAt=Instant.now(); }
    public void scheduleRetry(String error, Instant nextAttemptAt) {
        this.lastError=error; this.nextAttemptAt=nextAttemptAt; this.status=TransactionStatus.RETRY_PENDING;
        this.updatedAt=Instant.now();
    }
    public void markRetryExhausted(String error) {
        this.lastError=error; this.nextAttemptAt=null; this.status=TransactionStatus.RETRY_EXHAUSTED;
        this.updatedAt=Instant.now();
    }
    public void clearNextAttemptAt() { this.nextAttemptAt=null; this.updatedAt=Instant.now(); }
    public void markTransientFailureTriggered() { this.transientFailureTriggered=true; }
    public Long getId(){return id;} public String getTransactionId(){return transactionId;}
    public String getRequestId(){return requestId;} public String getAccountId(){return accountId;}
    public TransactionType getTransactionType(){return transactionType;} public BigDecimal getAmount(){return amount;}
    public String getCurrency(){return currency;} public Long getSequenceNumber(){return sequenceNumber;}
    public TransactionStatus getStatus(){return status;} public int getAttempts(){return attempts;}
    public String getLastError(){return lastError;} public Instant getCreatedAt(){return createdAt;}
    public Instant getUpdatedAt(){return updatedAt;} public Instant getNextAttemptAt(){return nextAttemptAt;}
    public boolean shouldSimulateTransientFailure(){return simulateTransientFailure;}
    public boolean isTransientFailureTriggered(){return transientFailureTriggered;}
}
