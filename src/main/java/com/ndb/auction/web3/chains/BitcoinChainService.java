package com.ndb.auction.web3.chains;

import com.ndb.auction.config.NetworkConfiguration;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;

import org.bitcoinj.core.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.*;
import org.bitcoinj.store.MemoryBlockStore;

import com.ndb.auction.models.wallet.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BitcoinChainService implements ChainService {

    @Value("${bitcoin.network:mainnet}")
    private String bitcoinNetwork;

    @Value("${bitcoin.node.url:}")
    private String bitcoinNodeUrl;

    @Value("${wallet.seed}")
    private String SEED_PHRASE;

    @Autowired
    private NetworkConfiguration networkConfig;

    private NetworkParameters networkParameters;
    private Wallet wallet;
    private PeerGroup peerGroup;
    private BlockChain blockChain;

    // Bitcoin fee estimation - using different fee levels based on network
    // congestion
    private static final BigDecimal DEFAULT_FEE = new BigDecimal("0.0001"); // ~10k sats - standard fee
    private static final BigDecimal TESTNET_FEE = new BigDecimal("0.00001"); // Lower fees on testnet

    private NetworkParameters getNetworkParameters() {
        if (networkParameters == null) {
            boolean isTestnet = networkConfig.isTestnetForNetwork("bitcoin");
            networkParameters = isTestnet ? TestNet3Params.get() : MainNetParams.get();
        }
        return networkParameters;
    }

    private Wallet getWallet() {
        if (wallet == null) {
            try {
                NetworkParameters params = getNetworkParameters();
                DeterministicSeed seed = new DeterministicSeed(SEED_PHRASE, null, "",
                        System.currentTimeMillis() / 1000);
                wallet = Wallet.fromSeed(params, seed, Script.ScriptType.P2WPKH);

                // Setup blockchain sync
                initializeBlockchain();
            } catch (Exception e) {
                log.error("Failed to initialize Bitcoin wallet: ", e);
                throw new RuntimeException("Failed to initialize Bitcoin wallet", e);
            }
        }
        return wallet;
    }

    private void initializeBlockchain() {
        try {
            NetworkParameters params = getNetworkParameters();

            // Use memory store for better performance in production
            MemoryBlockStore blockStore = new MemoryBlockStore(params);
            blockChain = new BlockChain(params, wallet, blockStore);

            peerGroup = new PeerGroup(params, blockChain);
            peerGroup.addWallet(wallet);

            // Start blockchain sync in background
            CompletableFuture.runAsync(() -> {
                try {
                    peerGroup.start();
                    peerGroup.downloadBlockChain();
                } catch (Exception e) {
                    log.error("Failed to sync Bitcoin blockchain: ", e);
                }
            });

        } catch (Exception e) {
            log.error("Failed to initialize Bitcoin blockchain: ", e);
            throw new RuntimeException("Failed to initialize Bitcoin blockchain", e);
        }
    }

    @Override
    public TransferResult sendToken(String tokenSymbol, String fromPrivateKey, String toAddress, BigDecimal amount) {
        try {
            if (!"BTC".equals(tokenSymbol.toUpperCase())) {
                return TransferResult.failed("Only BTC is supported on Bitcoin network");
            }

            Wallet btcWallet = getWallet();
            NetworkParameters params = getNetworkParameters();

            // Parse destination address
            Address destinationAddress = Address.fromString(params, toAddress);

            // Convert amount to satoshis
            Coin amountCoin = Coin.parseCoin(amount.toString());

            // Check if wallet has sufficient balance
            Coin balance = btcWallet.getBalance();
            if (balance.isLessThan(amountCoin)) {
                return TransferResult.failed("Insufficient balance. Available: " + balance.toFriendlyString());
            }

            // Use wallet's sendCoins method which is available in BitcoinJ 0.16.1
            try {
                // Ensure we have a connected peer group
                if (peerGroup == null || !peerGroup.isRunning()) {
                    return TransferResult.failed("Bitcoin network not connected. Please wait for synchronization.");
                }

                // Send using wallet's built-in send method - returns SendResult
                Wallet.SendResult sendResult = btcWallet.sendCoins(peerGroup, destinationAddress, amountCoin);

                if (sendResult != null && sendResult.tx != null) {
                    String txHash = sendResult.tx.getTxId().toString();

                    // Calculate fee - try to get actual fee, fallback to estimate
                    BigDecimal actualFee;
                    try {
                        Coin txFee = sendResult.tx.getFee();
                        if (txFee != null) {
                            actualFee = new BigDecimal(txFee.getValue()).divide(new BigDecimal(100000000)); // Convert
                                                                                                            // satoshis
                                                                                                            // to BTC
                        } else {
                            // Fallback to default fee estimate
                            boolean isTestnet = networkConfig.isTestnetForNetwork("bitcoin");
                            actualFee = isTestnet ? TESTNET_FEE : DEFAULT_FEE;
                        }
                    } catch (Exception feeException) {
                        log.warn("Could not determine transaction fee, using default: ", feeException);
                        boolean isTestnet = networkConfig.isTestnetForNetwork("bitcoin");
                        actualFee = isTestnet ? TESTNET_FEE : DEFAULT_FEE;
                    }

                    log.info("Successfully sent {} BTC to {} with txHash: {}", amount, toAddress, txHash);
                    return TransferResult.success(txHash, actualFee);
                } else {
                    return TransferResult.failed("Failed to create Bitcoin transaction");
                }
            } catch (InsufficientMoneyException ime) {
                return TransferResult.failed("Insufficient funds: " + ime.getMessage());
            } catch (Exception sendException) {
                log.error("Failed to send Bitcoin transaction: ", sendException);
                return TransferResult.failed("Bitcoin transfer failed: " + sendException.getMessage());
            }

        } catch (Exception e) {
            log.error("Failed to send {} BTC to {}: ", amount, toAddress, e);
            return TransferResult.failed("Bitcoin transfer failed: " + e.getMessage());
        }
    }

    @Override
    public WalletBalance getBalance(String address, String tokenSymbol) {
        try {
            if (!"BTC".equals(tokenSymbol.toUpperCase())) {
                return WalletBalance.zero(tokenSymbol);
            }

            Wallet btcWallet = getWallet();
            Coin balance = btcWallet.getBalance();
            BigDecimal btcBalance = new BigDecimal(balance.getValue()).divide(new BigDecimal(100000000)); // Convert
                                                                                                          // satoshis to
                                                                                                          // BTC

            return WalletBalance.of("BTC", btcBalance);
        } catch (Exception e) {
            log.error("Failed to get Bitcoin balance for {}: ", address, e);
            return WalletBalance.zero("BTC");
        }
    }

    @Override
    public List<String> getSupportedTokens() {
        // Bitcoin only supports BTC
        return Arrays.asList("BTC");
    }

    @Override
    public NetworkConfig getNetworkConfig() {
        boolean isTestnet = networkConfig.isTestnetForNetwork("bitcoin");
        return NetworkConfig.builder()
                .networkName(networkConfig.getNetworkDisplayName("bitcoin"))
                .networkType("BTC")
                .nativeCurrency("BTC")
                .rpcUrl(networkConfig.getRpcUrl("bitcoin"))
                .explorerUrl(networkConfig.getExplorerUrl("bitcoin"))
                .chainId(0) // Bitcoin doesn't use chain IDs
                .isTestnet(isTestnet)
                .averageFee(isTestnet ? TESTNET_FEE : DEFAULT_FEE)
                .confirmationBlocks(isTestnet ? 3 : 6) // Faster confirmations on testnet
                .build();
    }

    @Override
    public boolean checkTransactionStatus(String txHash) {
        try {
            Wallet btcWallet = getWallet();
            Sha256Hash hash = Sha256Hash.wrap(txHash);
            Transaction tx = btcWallet.getTransaction(hash);

            if (tx == null) {
                return false;
            }

            // Check if transaction has enough confirmations
            TransactionConfidence confidence = tx.getConfidence();
            return confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING &&
                    confidence.getDepthInBlocks() >= 1;
        } catch (Exception e) {
            log.error("Failed to check Bitcoin transaction status {}: ", txHash, e);
            return false;
        }
    }

    @Override
    public BigDecimal estimateFee(String tokenSymbol, BigDecimal amount) {
        if (!"BTC".equals(tokenSymbol.toUpperCase())) {
            return BigDecimal.ZERO;
        }

        // Return appropriate fee based on network configuration
        boolean isTestnet = networkConfig.isTestnetForNetwork("bitcoin");
        return isTestnet ? TESTNET_FEE : DEFAULT_FEE;
    }

    @Override
    public boolean isValidAddress(String address) {
        try {
            NetworkParameters params = getNetworkParameters();
            Address.fromString(params, address);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getNetworkType() {
        return "BTC";
    }

    // Bitcoin-specific helper methods

    /**
     * Get current Bitcoin wallet address
     */
    public String getCurrentAddress() {
        try {
            Wallet btcWallet = getWallet();
            return btcWallet.currentReceiveAddress().toString();
        } catch (Exception e) {
            log.error("Failed to get current Bitcoin address: ", e);
            return null;
        }
    }

    /**
     * Get Bitcoin wallet balance in different units
     */
    public Map<String, BigDecimal> getDetailedBalance() {
        try {
            Wallet btcWallet = getWallet();
            Coin balance = btcWallet.getBalance();

            Map<String, BigDecimal> balances = new HashMap<>();
            balances.put("BTC", new BigDecimal(balance.getValue()).divide(new BigDecimal(100000000)));
            balances.put("satoshis", new BigDecimal(balance.getValue()));
            balances.put("mBTC", new BigDecimal(balance.getValue()).divide(new BigDecimal(100000)));

            return balances;
        } catch (Exception e) {
            log.error("Failed to get detailed Bitcoin balance: ", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Get transaction history
     */
    public List<String> getRecentTransactions(int limit) {
        try {
            Wallet btcWallet = getWallet();
            Set<Transaction> transactions = btcWallet.getTransactions(false);

            return transactions.stream()
                    .sorted((t1, t2) -> t2.getUpdateTime().compareTo(t1.getUpdateTime()))
                    .limit(limit)
                    .map(tx -> tx.getTxId().toString())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get Bitcoin transaction history: ", e);
            return Collections.emptyList();
        }
    }

    /**
     * Get estimated fee for transaction size
     */
    public BigDecimal estimateFeeForSize(int txSizeBytes) {
        boolean isTestnet = networkConfig.isTestnetForNetwork("bitcoin");
        BigDecimal feePerKb = isTestnet ? TESTNET_FEE : DEFAULT_FEE;

        // Calculate fee based on transaction size
        BigDecimal txSizeKb = new BigDecimal(txSizeBytes).divide(new BigDecimal(1000));
        return feePerKb.multiply(txSizeKb);
    }

    /**
     * Get network status information
     */
    public Map<String, Object> getNetworkStatus() {
        Map<String, Object> status = new HashMap<>();
        try {
            status.put("connected", peerGroup != null && peerGroup.isRunning());
            status.put("peerCount", peerGroup != null ? peerGroup.getConnectedPeers().size() : 0);
            status.put("syncProgress", peerGroup != null ? peerGroup.getMostCommonChainHeight() : 0);
            status.put("isTestnet", networkConfig.isTestnetForNetwork("bitcoin"));

            if (wallet != null) {
                status.put("walletBalance", wallet.getBalance().toFriendlyString());
                status.put("walletAddress", wallet.currentReceiveAddress().toString());
            }
        } catch (Exception e) {
            log.error("Failed to get network status: ", e);
            status.put("error", e.getMessage());
        }
        return status;
    }

    /**
     * Check if wallet is synchronized
     */
    public boolean isWalletSynchronized() {
        try {
            return peerGroup != null && peerGroup.isRunning() && peerGroup.getConnectedPeers().size() > 0;
        } catch (Exception e) {
            log.error("Failed to check wallet synchronization status: ", e);
            return false;
        }
    }

    /**
     * Wait for wallet to synchronize
     */
    public CompletableFuture<Boolean> waitForSynchronization() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (peerGroup != null) {
                    peerGroup.downloadBlockChain();
                    return true;
                }
                return false;
            } catch (Exception e) {
                log.error("Failed during wallet synchronization: ", e);
                return false;
            }
        });
    }

    /**
     * Shutdown Bitcoin services properly
     */
    public void shutdown() {
        try {
            if (peerGroup != null && peerGroup.isRunning()) {
                peerGroup.stop();
                log.info("Bitcoin peer group stopped successfully");
            }
        } catch (Exception e) {
            log.error("Failed to shutdown Bitcoin services: ", e);
        }
    }
}