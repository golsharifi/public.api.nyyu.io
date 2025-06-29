package com.ndb.auction.web3.chains;

import com.ndb.auction.models.wallet.TransferResult;
import com.ndb.auction.models.wallet.WalletBalance;
import com.ndb.auction.models.wallet.NetworkConfig;

import java.math.BigDecimal;
import java.util.List;

/**
 * Universal interface for blockchain withdrawal operations
 * Each network implements this interface for consistent withdrawal
 * functionality
 */
public interface ChainService {

    /**
     * Send tokens on this network (withdrawal operation)
     */
    TransferResult sendToken(String tokenSymbol, String fromPrivateKey, String toAddress, BigDecimal amount);

    /**
     * Get balance for a specific token
     */
    WalletBalance getBalance(String address, String tokenSymbol);

    /**
     * Get list of supported tokens on this network
     */
    List<String> getSupportedTokens();

    /**
     * Get network configuration
     */
    NetworkConfig getNetworkConfig();

    /**
     * Check transaction status
     */
    boolean checkTransactionStatus(String txHash);

    /**
     * Estimate transaction fee
     */
    BigDecimal estimateFee(String tokenSymbol, BigDecimal amount);

    /**
     * Validate address format for this network
     */
    boolean isValidAddress(String address);

    /**
     * Get network type identifier
     */
    String getNetworkType();
}