package com.example.transactions.repo;
import com.example.transactions.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    boolean existsByTransactionId(String transactionId);
    List<LedgerEntry> findAllByOrderByCreatedAtAsc();
}
