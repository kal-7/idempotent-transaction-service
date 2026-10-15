package com.example.transactions;

import com.example.transactions.api.TransactionRequest;
import com.example.transactions.model.TransactionType;
import com.example.transactions.repo.*;
import com.example.transactions.service.TransactionIngestionService;
import com.example.transactions.service.TransactionProcessor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "app.worker.fixed-delay-ms=600000","app.worker.retry.initial-delay-ms=500","app.worker.retry.max-delay-ms=1000"})
class TransactionApiIntegrationTest {
    @Autowired TransactionIngestionService ingestion;
    @Autowired TransactionProcessor processor;
    @Autowired TransactionRecordRepository transactions;
    @Autowired AccountRepository accounts;
    @Autowired LedgerEntryRepository ledger;

    @BeforeEach void clean() { ledger.deleteAll(); transactions.deleteAll(); accounts.deleteAll(); }

    private TransactionRequest req(String txn,String req,String account,long seq,TransactionType type,String amount,boolean fail) {
        return new TransactionRequest(txn,req,account,type,new BigDecimal(amount),"INR",seq,fail);
    }

    @Test void duplicateDoesNotCreateSecondTransactionOrLedgerEffect() {
        ingestion.ingest(req("T1","R1","A1",1,TransactionType.CREDIT,"100",false));
        ingestion.ingest(req("T1","R2","A1",1,TransactionType.CREDIT,"100",false));
        assertThat(transactions.count()).isEqualTo(1);
        processor.processOne(transactions.findByTransactionId("T1").orElseThrow().getId());
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(accounts.findByAccountIdAndCurrency("A1","INR").orElseThrow().getBalance())
            .isEqualByComparingTo("100");
    }

    @Test void futureSequenceWaitsUntilEarlierSequenceArrives() {
        ingestion.ingest(req("T2","R2","A2",2,TransactionType.DEBIT,"40",false));
        Long second=transactions.findByTransactionId("T2").orElseThrow().getId();
        processor.processOne(second);
        assertThat(transactions.findById(second).orElseThrow().getStatus().name()).isEqualTo("PENDING_SEQUENCE");
        ingestion.ingest(req("T1","R1","A2",1,TransactionType.CREDIT,"100",false));
        Long first=transactions.findByTransactionId("T1").orElseThrow().getId();
        processor.processOne(first);
        processor.processOne(second);
        assertThat(accounts.findByAccountIdAndCurrency("A2","INR").orElseThrow().getBalance()).isEqualByComparingTo("60");
    }

    @Test void transientFailureRetriesWithoutDuplicateEffect() throws InterruptedException {
        ingestion.ingest(req("T3","R3","A3",1,TransactionType.CREDIT,"500",true));
        Long id=transactions.findByTransactionId("T3").orElseThrow().getId();
        processor.processOne(id);
        var retry=transactions.findById(id).orElseThrow();
        assertThat(retry.getStatus().name()).isEqualTo("RETRY_PENDING");
        assertThat(retry.getNextAttemptAt()).isAfter(Instant.now());
        processor.processOne(id);
        assertThat(transactions.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
        waitForNextAttempt(id);
        processor.processOne(id);
        assertThat(transactions.findById(id).orElseThrow().getStatus().name()).isEqualTo("PROCESSED");
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(accounts.findByAccountIdAndCurrency("A3","INR").orElseThrow().getBalance()).isEqualByComparingTo("500");
    }

    @Test void concurrentDuplicateSubmissionsCreateOneTransaction() throws Exception {
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> submissions=new ArrayList<>();
            for (int i=0; i<8; i++) {
                final int index=i;
                submissions.add(pool.submit(() -> {
                    start.await();
                    return ingestion.ingest(req("T4","R4-"+index,"A4",1,TransactionType.CREDIT,"100",false));
                }));
            }
            start.countDown();
            for (Future<?> submission:submissions) submission.get(10,TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test void concurrentWorkerAttemptsApplyLedgerEffectOnce() throws Exception {
        ingestion.ingest(req("T5","R5","A5",1,TransactionType.CREDIT,"125",false));
        Long id=transactions.findByTransactionId("T5").orElseThrow().getId();
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> attempts=new ArrayList<>();
            for (int i=0; i<8; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    processor.processOne(id);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> attempt:attempts) attempt.get(10,TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledger.count()).isEqualTo(1);
        assertThat(accounts.findByAccountIdAndCurrency("A5","INR").orElseThrow().getBalance())
            .isEqualByComparingTo("125");
        assertThat(transactions.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test void failedProcessingIsRetriedWithBackoffAndEventuallyExhausted() throws InterruptedException {
        ingestion.ingest(req("T6","R6","A6",1,TransactionType.CREDIT,"100",false));
        Long id=transactions.findByTransactionId("T6").orElseThrow().getId();
        processor.recordProcessingFailure(id,"TestFailure");
        var firstRetry=transactions.findById(id).orElseThrow().getNextAttemptAt();
        assertThat(firstRetry).isAfter(Instant.now());
        processor.processOne(id);
        assertThat(transactions.findById(id).orElseThrow().getAttempts()).isEqualTo(1);

        waitForNextAttempt(id);
        processor.recordProcessingFailure(id,"TestFailure");
        var secondRetry=transactions.findById(id).orElseThrow().getNextAttemptAt();
        assertThat(Duration.between(Instant.now(), secondRetry).toMillis()).isBetween(900L,1000L);

        waitForNextAttempt(id);
        processor.recordProcessingFailure(id,"TestFailure");
        var exhausted=transactions.findById(id).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(com.example.transactions.model.TransactionStatus.RETRY_EXHAUSTED);
        assertThat(exhausted.getAttempts()).isEqualTo(3);
        assertThat(exhausted.getNextAttemptAt()).isNull();
        processor.processOne(id);
        assertThat(transactions.findById(id).orElseThrow().getStatus())
            .isEqualTo(com.example.transactions.model.TransactionStatus.RETRY_EXHAUSTED);
    }

    private void waitForNextAttempt(Long id) throws InterruptedException {
        Instant nextAttempt=transactions.findById(id).orElseThrow().getNextAttemptAt();
        long waitMs=Duration.between(Instant.now(), nextAttempt).toMillis();
        if (waitMs>0) Thread.sleep(waitMs+5);
    }
}
