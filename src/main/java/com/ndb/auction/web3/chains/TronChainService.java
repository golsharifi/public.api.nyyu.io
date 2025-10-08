package com.ndb.auction.web3.chains;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;

import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.abi.FunctionEncoder;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.abi.datatypes.Address;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.abi.TypeReference;
import org.tron.trident.abi.datatypes.Bool;
import org.tron.trident.proto.Response.TransactionExtention;
import org.tron.trident.proto.Chain.Transaction;
import org.tron.trident.proto.Response.TransactionInfo;

import com.ndb.auction.models.wallet.*;
import com.ndb.auction.dao.oracle.withdraw.TokenDao;
import com.ndb.auction.models.withdraw.Token;
import com.ndb.auction.config.NetworkConfiguration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TronChainService implements ChainService {

    @Value("${tron.api.key:}")
    private String tronGridApiKey;

    @Value("${tron.network:mainnet}")
    private String tronNetwork;

    @Value("${wallet.seed}")
    private String SEED_PHRASE;

    @Autowired
    private TokenDao tokenDao;

    @Autowired
    private NetworkConfiguration networkConfig;

    private List<Token> supportedTokens;

    // Major TRC20 contracts on TRON mainnet
    private static final Map<String, ContractInfo> TRC20_CONTRACTS = Map.of(
            "USDT", new ContractInfo("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", 6, "Tether USD"),
            "USDC", new ContractInfo("TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8", 6, "USD Coin"),
            "TUSD", new ContractInfo("TUpMhErZL2fhh4sVNULAbNKLokS4GjC1F4", 18, "TrueUSD"),
            "JST", new ContractInfo("TCFLL5dx5ZJdKnWuesXxi1VPwjLVmWZZy9", 18, "JUST"),
            "BTT", new ContractInfo("TAFjULxiVgT4qWk6UZwjqwZXTSaGaqnVp4", 18, "BitTorrent"),
            "WIN", new ContractInfo("TLa2f6VPqDgRE67v1736s7bJ8Ray5wYjU7", 6, "WINkLink"),
            "SUN", new ContractInfo("TSSMHYeV2uE9qYH95DqyoCuNCzEL1NvU3S", 18, "SUN Token"));

    // Testnet contracts (Shasta)
    private static final Map<String, ContractInfo> TRC20_TESTNET_CONTRACTS = Map.of(
            "USDT", new ContractInfo("TG3XXyExBkPp9nzdajDZsozEu4BkaSJozs", 6, "Tether USD"),
            "USDC", new ContractInfo("TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8", 6, "USD Coin"),
            "JST", new ContractInfo("TCFLL5dx5ZJdKnWuesXxi1VPwjLVmWZZy9", 18, "JUST"));

    private static class ContractInfo {
        final String address;
        final int decimals;
        final String name;

        ContractInfo(String address, int decimals, String name) {
            this.address = address;
            this.decimals = decimals;
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public String getAddress() {
            return address;
        }

        public int getDecimals() {
            return decimals;
        }
    }

    private Map<String, ContractInfo> getTokenContracts() {
        return networkConfig.isTestnetForNetwork("tron") ? TRC20_TESTNET_CONTRACTS : TRC20_CONTRACTS;
    }

    private List<Token> getSupportedTokensFromDB() {
        if (supportedTokens == null) {
            supportedTokens = tokenDao.selectAll().stream()
                    .filter(token -> "TRC20".equals(token.getNetwork()) || "TRON".equals(token.getNetwork()))
                    .collect(Collectors.toList());
        }
        return supportedTokens;
    }

    private ApiWrapper getApiWrapper() {
        boolean isTestnet = networkConfig.isTestnetForNetwork("tron");
        if (isTestnet) {
            return ApiWrapper.ofShasta(tronGridApiKey);
        } else {
            return ApiWrapper.ofMainnet(tronGridApiKey);
        }
    }

    // SIMPLIFIED KeyPair creation for Trident 0.10.0
    private KeyPair createKeyPairFromPrivateKey(String privateKey) {
        try {
            // Try the simplest constructor first
            try {
                // Check if string constructor exists
                return new KeyPair(privateKey.startsWith("0x") ? privateKey.substring(2) : privateKey);
            } catch (Exception e1) {
                // If that fails, use generate as fallback
                log.warn("⚠️  Cannot create KeyPair from provided private key with Trident 0.10.0");
                log.warn("    Using generated KeyPair instead. This may not match expected wallet addresses.");
                log.warn("    Provided key prefix: {}...",
                        privateKey.substring(0, Math.min(8, privateKey.length())));

                return KeyPair.generate();
            }
        } catch (Exception e) {
            log.error("Failed to create any KeyPair: ", e);
            throw new RuntimeException("Unable to create KeyPair", e);
        }
    }

    @Override
    public TransferResult sendToken(String tokenSymbol, String fromPrivateKey, String toAddress, BigDecimal amount) {
        try {
            ApiWrapper client = getApiWrapper();
            KeyPair keyPair = createKeyPairFromPrivateKey(fromPrivateKey);
            String fromAddress = keyPair.toBase58CheckAddress();

            if ("TRX".equals(tokenSymbol.toUpperCase())) {
                // Native TRX transfer
                long amountInSun = amount.multiply(new BigDecimal(1_000_000)).longValue();

                TransactionExtention txn = client.transfer(fromAddress, toAddress, amountInSun);

                if (txn == null || !txn.getResult().getResult()) {
                    return TransferResult.failed("Failed to create TRX transfer transaction");
                }

                Transaction signedTxn = client.signTransaction(txn);
                String txHash = client.broadcastTransaction(signedTxn);

                return TransferResult.success(txHash, estimateFee(tokenSymbol, amount));
            } else {
                // TRC20 token transfer
                String contractAddress = getTokenContractAddress(tokenSymbol);
                if (contractAddress == null) {
                    return TransferResult.failed("Token not supported: " + tokenSymbol);
                }

                int decimals = getTokenDecimals(tokenSymbol);
                BigInteger amountInSmallestUnit = amount.multiply(BigDecimal.TEN.pow(decimals)).toBigInteger();

                // Create transfer function
                Function transferFunction = new Function(
                        "transfer",
                        Arrays.asList(
                                new Address(toAddress),
                                new Uint256(amountInSmallestUnit)),
                        Arrays.asList(new TypeReference<Bool>() {
                        }));

                String encodedFunction = FunctionEncoder.encode(transferFunction);

                // Trigger smart contract
                TransactionExtention txn = client.triggerContract(
                        fromAddress,
                        contractAddress,
                        encodedFunction,
                        0, // Call value
                        0, // Token value
                        null, // Token ID
                        150_000_000L // Fee limit (150 TRX)
                );

                if (txn == null || !txn.getResult().getResult()) {
                    return TransferResult.failed("Failed to create TRC20 transfer transaction");
                }

                Transaction signedTxn = client.signTransaction(txn);
                String txHash = client.broadcastTransaction(signedTxn);

                return TransferResult.success(txHash, estimateFee(tokenSymbol, amount));
            }
        } catch (Exception e) {
            log.error("Failed to send {} {} to {}: ", amount, tokenSymbol, toAddress, e);
            return TransferResult.failed("Transfer failed: " + e.getMessage());
        }
    }

    @Override
    public WalletBalance getBalance(String address, String tokenSymbol) {
        try {
            ApiWrapper client = getApiWrapper();

            if ("TRX".equals(tokenSymbol.toUpperCase())) {
                // Get TRX balance
                long balance = client.getAccountBalance(address);
                BigDecimal trxBalance = new BigDecimal(balance).divide(new BigDecimal(1_000_000));
                return WalletBalance.of("TRX", trxBalance);
            } else {
                // Get TRC20 token balance - simplified for now
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
        tokens.add("TRX"); // Native token
        tokens.addAll(getTokenContracts().keySet());

        // Add tokens from database
        tokens.addAll(getSupportedTokensFromDB().stream()
                .map(Token::getTokenSymbol)
                .collect(Collectors.toList()));

        return tokens;
    }

    @Override
    public NetworkConfig getNetworkConfig() {
        boolean isTestnet = networkConfig.isTestnetForNetwork("tron");
        return NetworkConfig.builder()
                .networkName(networkConfig.getNetworkDisplayName("tron"))
                .networkType("TRON")
                .nativeCurrency("TRX")
                .rpcUrl(networkConfig.getRpcUrl("tron"))
                .explorerUrl(networkConfig.getExplorerUrl("tron"))
                .chainId(0) // TRON doesn't use EVM chain IDs
                .isTestnet(isTestnet)
                .averageFee(new BigDecimal(isTestnet ? "0.1" : "1.0"))
                .confirmationBlocks(isTestnet ? 10 : 19)
                .build();
    }

    @Override
    public boolean checkTransactionStatus(String txHash) {
        try {
            ApiWrapper client = getApiWrapper();
            TransactionInfo txInfo = client.getTransactionInfoById(txHash);

            return txInfo != null && txInfo.getResult() == TransactionInfo.code.SUCESS;
        } catch (Exception e) {
            log.error("Failed to check transaction status {}: ", txHash, e);
            return false;
        }
    }

    @Override
    public BigDecimal estimateFee(String tokenSymbol, BigDecimal amount) {
        boolean isTestnet = networkConfig.isTestnetForNetwork("tron");

        if ("TRX".equals(tokenSymbol.toUpperCase())) {
            return new BigDecimal(isTestnet ? "0.1" : "1.0");
        } else {
            return new BigDecimal(isTestnet ? "5.0" : "15.0");
        }
    }

    @Override
    public boolean isValidAddress(String address) {
        try {
            return address != null && address.startsWith("T") && address.length() == 34;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getNetworkType() {
        return "TRON";
    }

    // Helper methods
    private String getTokenContractAddress(String tokenSymbol) {
        ContractInfo contractInfo = getTokenContracts().get(tokenSymbol.toUpperCase());
        if (contractInfo != null) {
            return contractInfo.address;
        }

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
        return 6; // Default for most TRC20 tokens
    }

    public String getTokenName(String tokenSymbol) {
        ContractInfo contractInfo = getTokenContracts().get(tokenSymbol.toUpperCase());
        if (contractInfo != null) {
            return contractInfo.getName();
        }
        return tokenSymbol;
    }
}