package com.ndb.auction.web3.chains;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;

import com.ndb.auction.solanaj.data.SolanaAccount;
import com.ndb.auction.solanaj.data.SolanaTransaction;
import com.ndb.auction.solanaj.data.SolanaPublicKey;
import com.ndb.auction.solanaj.api.rpc.SolanaRpcApi;
import com.ndb.auction.solanaj.api.rpc.SolanaRpcClient;
import com.ndb.auction.solanaj.api.rpc.Cluster;
import com.ndb.auction.solanaj.program.SystemProgram;
import com.paymennt.crypto.lib.Base58;

import com.ndb.auction.models.wallet.*;
import com.ndb.auction.dao.oracle.withdraw.TokenDao;
import com.ndb.auction.models.withdraw.Token;
import com.ndb.auction.config.NetworkConfiguration;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SolanaChainService implements ChainService {

    @Value("${solana.rpc.url:https://api.mainnet-beta.solana.com}")
    private String solanaRpcUrl;

    @Value("${wallet.seed}")
    private String SEED_PHRASE;

    @Autowired
    private TokenDao tokenDao;

    @Autowired
    private NetworkConfiguration networkConfig;

    private List<Token> supportedTokens;
    private SolanaRpcClient rpcClient;

    // Major SPL token addresses on Solana mainnet
    private static final Map<String, ContractInfo> SPL_CONTRACTS = Map.of(
            "USDT", new ContractInfo("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", 6, "Tether USD"),
            "USDC", new ContractInfo("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 6, "USD Coin"),
            "RAY", new ContractInfo("4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R", 6, "Raydium"),
            "SRM", new ContractInfo("SRMuApVNdxXokk5GT7XD5cUUgXMBCoAz2LHeuAoKWRt", 6, "Serum"),
            "SAMO", new ContractInfo("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", 9, "Samoyedcoin"),
            "COPE", new ContractInfo("8HGyAAB1yoM1ttS7pXjHMa3dukTFGQWKb5YY6GrtCjU", 6, "COPE"),
            "FIDA", new ContractInfo("EchesyfXePKdLtoiZSL8pBe8Myagyy8ZRqsACNCFGnvp", 6, "Bonfida"),
            "ORCA", new ContractInfo("orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE", 6, "Orca"),
            "MNGO", new ContractInfo("MangoCzJ36AjZyKwVj3VnYU4GTonjfVEnJmvvWaxLac", 6, "Mango"),
            "STEP", new ContractInfo("StepAscQoEioFxxWGnh2sLBDFp9d8rvKz2Yp39iDpyT", 9, "Step Finance"));

    // Testnet contracts (Devnet/Testnet)
    private static final Map<String, ContractInfo> SPL_TESTNET_CONTRACTS = Map.of(
            "USDT", new ContractInfo("BQcdHdAQW1hczDbBi9hiegXAR7A98Q9jx3X3iBBBDiq4", 6, "Tether USD"),
            "USDC", new ContractInfo("4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU", 6, "USD Coin"),
            "RAY", new ContractInfo("4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R", 6, "Raydium"),
            "SRM", new ContractInfo("SRMuApVNdxXokk5GT7XD5cUUgXMBCoAz2LHeuAoKWRt", 6, "Serum"));

    private static class ContractInfo {
        final String address;
        final int decimals;
        final String name;

        ContractInfo(String address, int decimals, String name) {
            this.address = address;
            this.decimals = decimals;
            this.name = name;
        }
    }

    private Map<String, ContractInfo> getTokenContracts() {
        return networkConfig.isTestnetForNetwork("solana") ? SPL_TESTNET_CONTRACTS : SPL_CONTRACTS;
    }

    private SolanaRpcClient getRpcClient() {
        if (rpcClient == null) {
            // Determine the appropriate cluster based on network configuration
            Cluster cluster;
            String rpcUrl = networkConfig.getRpcUrl("solana");

            if (rpcUrl.contains("testnet")) {
                cluster = Cluster.TESTNET;
            } else if (rpcUrl.contains("devnet")) {
                cluster = Cluster.DEVNET;
            } else {
                cluster = Cluster.MAINNET;
            }

            rpcClient = new SolanaRpcClient(cluster);
        }
        return rpcClient;
    }

    private List<Token> getSupportedTokensFromDB() {
        if (supportedTokens == null) {
            supportedTokens = tokenDao.selectAll().stream()
                    .filter(token -> "SPL".equals(token.getNetwork()) || "SOLANA".equals(token.getNetwork()))
                    .collect(Collectors.toList());
        }
        return supportedTokens;
    }

    @Override
    public TransferResult sendToken(String tokenSymbol, String fromPrivateKey, String toAddress, BigDecimal amount) {
        try {
            SolanaRpcClient client = getRpcClient();
            SolanaAccount fromAccount = SolanaAccount.fromSecret(Base58.decode(fromPrivateKey));
            SolanaRpcApi api = client.getApi();

            if ("SOL".equals(tokenSymbol.toUpperCase())) {
                // Native SOL transfer
                long lamports = amount.multiply(new BigDecimal(1_000_000_000L)).longValue(); // Convert SOL to lamports

                // Create transaction with SystemProgram transfer instruction
                SolanaTransaction transaction = new SolanaTransaction();
                transaction.addInstruction(
                        SystemProgram.transfer(
                                fromAccount.getPublicKey(),
                                new SolanaPublicKey(toAddress),
                                lamports));

                // Set recent blockhash and fee payer
                transaction.setRecentBlockHash(api.getRecentBlockhash());
                transaction.setFeePayer(fromAccount.getPublicKey());

                // Sign the transaction
                transaction.sign(fromAccount);

                // Send the transaction
                String txHash = api.sendTransaction(transaction);

                if (txHash != null && !txHash.isEmpty()) {
                    return TransferResult.success(txHash, estimateFee(tokenSymbol, amount));
                } else {
                    return TransferResult.failed("Failed to send SOL transaction");
                }
            } else {
                // SPL token transfer
                String mintAddress = getTokenContractAddress(tokenSymbol);
                if (mintAddress == null) {
                    return TransferResult.failed("Token not supported on Solana: " + tokenSymbol);
                }

                // Convert amount to proper decimals
                int decimals = getTokenDecimals(tokenSymbol);
                long amountInSmallestUnit = amount.multiply(BigDecimal.TEN.pow(decimals)).longValue();

                // Use the existing signAndSendTokenTransaction method
                String txHash = api.signAndSendTokenTransaction(
                        mintAddress,
                        fromAccount, // fee payer
                        fromAccount, // sender
                        toAddress, // recipient
                        amountInSmallestUnit);

                if (txHash != null && !txHash.isEmpty()) {
                    return TransferResult.success(txHash, estimateFee(tokenSymbol, amount));
                } else {
                    return TransferResult.failed("Failed to send SPL token transaction");
                }
            }
        } catch (Exception e) {
            log.error("Failed to send {} {} to {} on Solana: ", amount, tokenSymbol, toAddress, e);
            return TransferResult.failed("Solana transfer failed: " + e.getMessage());
        }
    }

    @Override
    public WalletBalance getBalance(String address, String tokenSymbol) {
        try {
            SolanaRpcClient client = getRpcClient();
            SolanaRpcApi api = client.getApi();

            if ("SOL".equals(tokenSymbol.toUpperCase())) {
                // Get SOL balance
                Long lamports = api.getBalance(address);
                if (lamports != null) {
                    BigDecimal solBalance = new BigDecimal(lamports).divide(new BigDecimal(1_000_000_000L)); // Convert
                                                                                                             // lamports
                                                                                                             // to SOL
                    return WalletBalance.of("SOL", solBalance);
                }
                return WalletBalance.zero("SOL");
            } else {
                // Get SPL token balance
                String mintAddress = getTokenContractAddress(tokenSymbol);
                if (mintAddress == null) {
                    return WalletBalance.zero(tokenSymbol);
                }

                try {
                    // Get token account and balance
                    String tokenAccount = api.getTokenAccount(address, mintAddress).getAddress();
                    if (tokenAccount != null) {
                        long tokenBalance = api.getTokenAccountBalance(tokenAccount);
                        int decimals = getTokenDecimals(tokenSymbol);
                        BigDecimal balance = new BigDecimal(tokenBalance).divide(BigDecimal.TEN.pow(decimals));
                        return WalletBalance.of(tokenSymbol, balance);
                    }
                } catch (Exception e) {
                    log.debug("Token account not found for {} {}: {}", tokenSymbol, address, e.getMessage());
                }

                return WalletBalance.zero(tokenSymbol);
            }
        } catch (Exception e) {
            log.error("Failed to get balance for {} {}: ", tokenSymbol, address, e);
            return WalletBalance.zero(tokenSymbol);
        }
    }

    @Override
    public List<String> getSupportedTokens() {
        List<String> tokens = new ArrayList<>();
        tokens.add("SOL"); // Native token
        tokens.addAll(getTokenContracts().keySet());

        // Add tokens from database
        tokens.addAll(getSupportedTokensFromDB().stream()
                .map(Token::getTokenSymbol)
                .collect(Collectors.toList()));

        return tokens;
    }

    @Override
    public NetworkConfig getNetworkConfig() {
        boolean isTestnet = networkConfig.isTestnetForNetwork("solana");
        return NetworkConfig.builder()
                .networkName(networkConfig.getNetworkDisplayName("solana"))
                .networkType("SPL")
                .nativeCurrency("SOL")
                .rpcUrl(networkConfig.getRpcUrl("solana"))
                .explorerUrl(networkConfig.getExplorerUrl("solana"))
                .chainId(0) // Solana doesn't use EVM chain IDs
                .isTestnet(isTestnet)
                .averageFee(new BigDecimal(isTestnet ? "0.000005" : "0.000005")) // Very low fees on Solana
                .confirmationBlocks(1) // Fast confirmations on Solana
                .build();
    }

    @Override
    public boolean checkTransactionStatus(String txHash) {
        try {
            SolanaRpcClient client = getRpcClient();
            SolanaRpcApi api = client.getApi();
            // Check transaction confirmation
            return api.getTransaction(txHash) != null;
        } catch (Exception e) {
            log.error("Failed to check Solana transaction status {}: ", txHash, e);
            return false;
        }
    }

    @Override
    public BigDecimal estimateFee(String tokenSymbol, BigDecimal amount) {
        // Remove unused variable warning by using the network configuration
        // appropriately
        boolean isTestnet = networkConfig.isTestnetForNetwork("solana");

        if ("SOL".equals(tokenSymbol.toUpperCase())) {
            return new BigDecimal("0.000005"); // ~0.000005 SOL for native transfers
        } else {
            return new BigDecimal("0.00001"); // ~0.00001 SOL for SPL token transfers (higher due to more complex
                                              // instructions)
        }
    }

    @Override
    public boolean isValidAddress(String address) {
        try {
            // Solana addresses are base58 encoded and typically 32-44 characters
            if (address == null || address.length() < 32 || address.length() > 44) {
                return false;
            }

            // Try to decode as base58 to validate format
            Base58.decode(address);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getNetworkType() {
        return "SPL";
    }

    // Helper methods

    private String getTokenContractAddress(String tokenSymbol) {
        // Check common contracts first
        ContractInfo contractInfo = getTokenContracts().get(tokenSymbol.toUpperCase());
        if (contractInfo != null) {
            return contractInfo.address;
        }

        // Check database
        Token token = getSupportedTokensFromDB().stream()
                .filter(t -> tokenSymbol.equalsIgnoreCase(t.getTokenSymbol()))
                .findFirst()
                .orElse(null);

        return token != null ? token.getAddress() : null;
    }

    private int getTokenDecimals(String tokenSymbol) {
        ContractInfo contractInfo = getTokenContracts().get(tokenSymbol.toUpperCase());
        if (contractInfo != null) {
            return contractInfo.decimals;
        }
        return 9; // Default for most SPL tokens
    }

    /**
     * Generate Solana wallet from seed phrase
     */
    public SolanaAccount generateWalletFromSeed() {
        try {
            // This is a simplified implementation
            // You would need to implement proper BIP44 derivation for Solana
            // For now, this is a placeholder that would need proper seed phrase handling
            byte[] seed = SEED_PHRASE.getBytes(); // This should be properly derived from mnemonic
            return SolanaAccount.fromSeed(seed);
        } catch (Exception e) {
            log.error("Failed to generate Solana wallet from seed: ", e);
            throw new RuntimeException("Failed to generate Solana wallet", e);
        }
    }

    /**
     * Get current Solana wallet address
     */
    public String getCurrentAddress() {
        try {
            SolanaAccount account = generateWalletFromSeed();
            return account.getPublicKey().toBase58();
        } catch (Exception e) {
            log.error("Failed to get current Solana address: ", e);
            return null;
        }
    }

    /**
     * Set recent blockhash for transaction
     */
    private void setRecentBlockHash(SolanaTransaction transaction) {
        try {
            SolanaRpcApi api = getRpcClient().getApi();
            transaction.setRecentBlockHash(api.getRecentBlockhash());
        } catch (Exception e) {
            log.error("Failed to set recent blockhash: ", e);
            throw new RuntimeException("Failed to set recent blockhash", e);
        }
    }

    /**
     * Set fee payer for transaction
     */
    private void setFeePayer(SolanaTransaction transaction, SolanaPublicKey feePayer) {
        transaction.setFeePayer(feePayer);
    }
}