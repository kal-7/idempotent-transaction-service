package com.example.transactions.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name="accounts", uniqueConstraints=@UniqueConstraint(name="uk_account_currency", columnNames={"account_id","currency"}))
public class Account {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="account_id", nullable=false) private String accountId;
    @Column(nullable=false, length=3) private String currency;
    @Column(nullable=false, precision=19, scale=2) private BigDecimal balance = BigDecimal.ZERO;
    @Column(nullable=false) private long lastProcessedSequence = 0;
    protected Account() {}
    public Account(String accountId, String currency) { this.accountId=accountId; this.currency=currency; }
    public void apply(BigDecimal delta) { balance=balance.add(delta); }
    public void setLastProcessedSequence(long sequence) { lastProcessedSequence=sequence; }
    public Long getId(){return id;} public String getAccountId(){return accountId;}
    public String getCurrency(){return currency;} public BigDecimal getBalance(){return balance;}
    public long getLastProcessedSequence(){return lastProcessedSequence;}
}
