package com.example.transactions.repo;

import com.example.transactions.model.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, Long> {
    Optional<TransactionRecord> findByTransactionId(String transactionId);
    Optional<TransactionRecord> findByRequestId(String requestId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionRecord t where t.id = :id")
    Optional<TransactionRecord> findByIdForUpdate(@Param("id") Long id);
    @Query("select t from TransactionRecord t where t.status in " +
        "(com.example.transactions.model.TransactionStatus.RECEIVED, " +
        "com.example.transactions.model.TransactionStatus.PENDING_SEQUENCE) " +
        "or (t.status = com.example.transactions.model.TransactionStatus.RETRY_PENDING " +
        "and (t.nextAttemptAt is null or t.nextAttemptAt <= :now)) order by t.createdAt")
    Page<TransactionRecord> findReadyToProcess(@Param("now") Instant now, Pageable pageable);
    List<TransactionRecord> findByAccountIdAndStatusInOrderBySequenceNumberAsc(String accountId, Collection<TransactionStatus> statuses);
    long countByStatus(TransactionStatus status);
}
