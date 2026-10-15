package com.example.transactions.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name="ledger_entries", uniqueConstraints=@UniqueConstraint(name="uk_ledger_transaction", columnNames="transaction_id"))
public class LedgerEntry {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="transaction_id", nullable=false, updatable=false) private String transactionId;
    @Column(nullable=false, updatable=false) private String accountId;
    @Column(nullable=false, precision=19, scale=2, updatable=false) private BigDecimal amount;
    @Column(nullable=false, length=3, updatable=false) private String currency;
    @Column(nullable=false, updatable=false) private Instant createdAt=Instant.now();
    protected LedgerEntry() {}
    public LedgerEntry(String txn, String account, BigDecimal amount, String currency) {
        this.transactionId=txn; this.accountId=account; this.amount=amount; this.currency=currency;
    }
    public String getTransactionId(){return transactionId;} public String getAccountId(){return accountId;}
    public BigDecimal getAmount(){return amount;} public String getCurrency(){return currency;}
    public Instant getCreatedAt(){return createdAt;}
}
