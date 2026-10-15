package com.example.transactions.repo;
import com.example.transactions.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountIdAndCurrency(String accountId, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountId = :accountId and a.currency = :currency")
    Optional<Account> findByAccountIdAndCurrencyForUpdate(
        @Param("accountId") String accountId, @Param("currency") String currency);
}
