package com.ndb.auction.models.wallet;

import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TransferResult {
    private boolean success;
    private String transactionHash;
    private String errorMessage;
    private BigDecimal fee;
    private long timestamp;

    public static TransferResult success(String txHash, BigDecimal fee) {
        return TransferResult.builder()
                .success(true)
                .transactionHash(txHash)
                .fee(fee)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static TransferResult success(String txHash) {
        return success(txHash, BigDecimal.ZERO);
    }

    public static TransferResult failed(String errorMessage) {
        return TransferResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
