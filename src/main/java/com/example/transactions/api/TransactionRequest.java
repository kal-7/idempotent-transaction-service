package com.example.transactions.api;

import com.example.transactions.model.TransactionType;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record TransactionRequest(
    @NotBlank String transactionId,
    @NotBlank String requestId,
    @NotBlank String accountId,
    @NotNull TransactionType transactionType,
    @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
    @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter uppercase code") String currency,
    @NotNull @Positive Long sequenceNumber,
    // Assessment-only switch to simulate a transient failure on the first processing attempt.
    Boolean simulateTransientFailure
) {}
