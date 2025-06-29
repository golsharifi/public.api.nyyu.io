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
public class NetworkConfig {
    private String networkName;
    private String networkType; // EVM, UTXO, SOLANA, TRON, etc.
    private String nativeCurrency;
    private String rpcUrl;
    private String explorerUrl;
    private int chainId; // For EVM networks
    private boolean isTestnet;
    private BigDecimal averageFee;
    private int confirmationBlocks;
}