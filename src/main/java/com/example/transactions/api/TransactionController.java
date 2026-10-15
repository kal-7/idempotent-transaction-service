package com.example.transactions.api;

import com.example.transactions.model.*;
import com.example.transactions.repo.*;
import com.example.transactions.service.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class TransactionController {
    private final TransactionIngestionService ingestion;
    private final TransactionRecordRepository transactions;
    private final AccountRepository accounts;
    private final LedgerEntryRepository ledger;
    public TransactionController(TransactionIngestionService ingestion, TransactionRecordRepository transactions,
            AccountRepository accounts, LedgerEntryRepository ledger) {
        this.ingestion=ingestion; this.transactions=transactions; this.accounts=accounts; this.ledger=ledger;
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TransactionResponse submit(@Valid @RequestBody TransactionRequest request) {
        return ingestion.ingest(request);
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> all() {
        return transactions.findAll().stream().map(t -> TransactionResponse.from(t, null)).toList();
    }

    @GetMapping("/transactions/{transactionId}")
    public TransactionResponse get(@PathVariable String transactionId) {
        return transactions.findByTransactionId(transactionId)
            .map(t -> TransactionResponse.from(t, null))
            .orElseThrow(() -> new NoSuchElementException("Transaction not found"));
    }

    @GetMapping("/accounts/{accountId}/balance")
    public Map<String,Object> balance(@PathVariable String accountId, @RequestParam(defaultValue="INR") String currency) {
        Account a=accounts.findByAccountIdAndCurrency(accountId,currency)
            .orElseGet(() -> new Account(accountId,currency));
        return Map.of("accountId",accountId,"currency",currency,"balance",a.getBalance(),
            "lastProcessedSequence",a.getLastProcessedSequence());
    }

    @GetMapping("/ledger")
    public List<Map<String,Object>> ledger() {
        return ledger.findAllByOrderByCreatedAtAsc().stream().map(e -> {
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("transactionId",e.getTransactionId()); row.put("accountId",e.getAccountId());
            row.put("amount",e.getAmount()); row.put("currency",e.getCurrency()); row.put("createdAt",e.getCreatedAt());
            return row;
        }).toList();
    }

    @GetMapping("/summary")
    public Map<String,Object> summary() {
        Map<String,Long> counts=Arrays.stream(TransactionStatus.values())
            .collect(Collectors.toMap(Enum::name, transactions::countByStatus, (a,b)->a, LinkedHashMap::new));
        return Map.of("countsByStatus",counts,"totalTransactions",transactions.count());
    }
}
