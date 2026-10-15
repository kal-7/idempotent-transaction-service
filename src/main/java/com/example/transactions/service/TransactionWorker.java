package com.example.transactions.service;

import com.example.transactions.model.TransactionStatus;
import com.example.transactions.repo.TransactionRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class TransactionWorker {
    private static final Logger log = LoggerFactory.getLogger(TransactionWorker.class);
    private final TransactionRecordRepository transactions;
    private final TransactionProcessor processor;

    public TransactionWorker(TransactionRecordRepository transactions, TransactionProcessor processor) {
        this.transactions = transactions;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString="${app.worker.fixed-delay-ms:1000}")
    public void poll() {
        var pending = transactions.findReadyToProcess(java.time.Instant.now(), PageRequest.of(0, 100));
        for (var record : pending) {
            try {
                // Calls the separate Spring bean so @Transactional on processOne is applied.
                processor.processOne(record.getId());
            } catch (Exception ex) {
                try {
                    processor.recordProcessingFailure(record.getId(), ex.getClass().getSimpleName());
                } catch (Exception recordingFailure) {
                    log.error("Failed to record processing failure transactionId={} processingErrorType={} recordingErrorType={}",
                        record.getTransactionId(), ex.getClass().getSimpleName(),
                        recordingFailure.getClass().getSimpleName(), recordingFailure);
                }
            }
        }
    }
}
