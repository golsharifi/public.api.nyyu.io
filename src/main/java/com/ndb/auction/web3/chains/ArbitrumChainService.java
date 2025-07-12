package com.ndb.auction.web3.chains;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.crypto.*;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.tx.Transfer;
import org.web3j.utils.Convert;
import org.web3j.protocol.core.DefaultBlockParameterName;

import com.ndb.auction.models.wallet.*;
import com.ndb.auction.dao.oracle.withdraw.TokenDao;
import com.ndb.auction.models.withdraw.Token;
import com.ndb.auction.config.NetworkConfiguration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ArbitrumChainService implements ChainService {

    @Value("${arbitrum.json.rpc:https://arb1.arbitrum.io/rpc}")
    private String ARBITRUM_RPC_URL;

    @Value("${wallet.seed}")
    private String SEED_PHRASE;

    @Autowired
    private TokenDao tokenDao;

    @Autowired
    private NetworkConfiguration networkConfig;

    private Web3j web3j;
    private List<Token> supportedTokens;
    private final BigInteger gasLimit = BigInteger.valueOf(21000);
    private final BigInteger erc20GasLimit = BigInteger.valueOf(100000);

    // Major tokens on Arbitrum One mainnet
    private static final Map<String, ContractInfo> ARBITRUM_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9", 6, "Tether USD"),
            "USDC", new ContractInfo("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6, "USD Coin"),
            "DAI", new ContractInfo("0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1", 18, "Dai Stablecoin"),
            "WETH", new ContractInfo("0x82aF49447D8a07e3bd95BD0d56f35241523fBab1", 18, "Wrapped Ether"),
            "WBTC", new ContractInfo("0x2f2a2543B76A4166549F7aaB2e75Bef0aefC5B0f", 8, "Wrapped BTC"),
            "LINK", new ContractInfo("0xf97f4df75117a78c1A5a0DBb814Af92458539FB4", 18, "ChainLink Token"),
            "UNI", new ContractInfo("0xFa7F8980b0f1E64A2062791cc3b0871572f1F7f0", 18, "Uniswap"),
            "ARB", new ContractInfo("0x912CE59144191C1204E64559FE8253a0e49E6548", 18, "Arbitrum"),
            "GMX", new ContractInfo("0xfc5A1A6EB076a2C7aD06eD22C90d7E710E35ad0a", 18, "GMX"),
            "MAGIC", new ContractInfo("0x539bdE0d7Dbd336b79148AA742883198BBF60342", 18, "MAGIC"));

    // Testnet contracts (Arbitrum Sepolia)
    private static final Map<String, ContractInfo> ARBITRUM_TESTNET_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0xf4423F4152966eBb106261740da907662A3569C5", 6, "Tether USD"),
            "USDC", new ContractInfo("0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d", 6, "USD Coin"),
            "LINK", new ContractInfo("0xd14838A68E8AFBAdE5efb411d5871ea0011AFd28", 18, "ChainLink Token"),
            "WETH", new ContractInfo("0x980B62Da83eFf3D4576C647993b0c1D7faf17c73", 18, "Wrapped Ether"));

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
        return networkConfig.isTestnetForNetwork("arbitrum") ? ARBITRUM_TESTNET_CONTRACTS : ARBITRUM_CONTRACTS;
    }

    private Web3j getWeb3j() {
        if (web3j == null) {
            String rpcUrl = networkConfig.getRpcUrl("arbitrum");
            web3j = Web3j.build(new HttpService(rpcUrl));
        }
        return web3j;
    }

    private List<Token> getSupportedTokensFromDB() {
        if (supportedTokens == null) {
            supportedTokens = tokenDao.selectAll().stream()
                    .filter(token -> "ARBITRUM".equals(token.getNetwork()) || "ARB".equals(token.getNetwork()))
                    .collect(Collectors.toList());
        }
        return supportedTokens;
    }

    @Override
    public TransferResult sendToken(String tokenSymbol, String fromPrivateKey, String toAddress, BigDecimal amount) {
        try {
            Web3j web3 = getWeb3j();
            Credentials credentials = Credentials.create(fromPrivateKey);

            if ("ETH".equals(tokenSymbol.toUpperCase())) {
                // Native ETH transfer on Arbitrum - FIXED
                CompletableFuture<TransactionReceipt> receiptFuture = Transfer.sendFunds(
                        web3, credentials, toAddress, amount, Convert.Unit.ETHER).sendAsync();

                TransactionReceipt receipt = receiptFuture.get();

                return TransferResult.success(
                        receipt.getTransactionHash(),
                        getTransactionFee(receipt));
            } else {
                // ERC20 token transfer on Arbitrum
                String contractAddress = getTokenContractAddress(tokenSymbol);
                if (contractAddress == null) {
                    return TransferResult.failed("Token not supported on Arbitrum: " + tokenSymbol);
                }

                EthGasPrice gasPrice = web3.ethGasPrice().send();

                ERC20 erc20 = ERC20.load(
                        contractAddress,
                        web3,
                        credentials,
                        gasPrice.getGasPrice(),
                        erc20GasLimit);

                // Get token decimals and convert amount
                int decimals = getTokenDecimals(tokenSymbol); // Now used
                BigInteger amountInSmallestUnit = amount.multiply(
                        new BigDecimal(10).pow(decimals)).toBigInteger();

                TransactionReceipt receipt = erc20.transfer(toAddress, amountInSmallestUnit).send();

                return TransferResult.success(
                        receipt.getTransactionHash(),
                        getTransactionFee(receipt));
            }
        } catch (Exception e) {
            log.error("Failed to send {} {} to {} on Arbitrum: ", amount, tokenSymbol, toAddress, e);
            return TransferResult.failed("Arbitrum transfer failed: " + e.getMessage());
        }
    }

    @Override
    public WalletBalance getBalance(String address, String tokenSymbol) {
        try {
            Web3j web3 = getWeb3j();

            if ("ETH".equals(tokenSymbol.toUpperCase())) {
                // Get ETH balance
                EthGetBalance ethGetBalance = web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
                BigDecimal ethBalance = Convert.fromWei(ethGetBalance.getBalance().toString(), Convert.Unit.ETHER);
                return WalletBalance.of(tokenSymbol, ethBalance); // FIXED
            } else {
                // Get ERC20 token balance
                String contractAddress = getTokenContractAddress(tokenSymbol);
                if (contractAddress == null) {
                    return WalletBalance.zero(tokenSymbol);
                }

                // Create dummy credentials for read-only operations
                Credentials dummyCredentials = Credentials
                        .create("0x0000000000000000000000000000000000000000000000000000000000000001");

                ERC20 erc20 = ERC20.load(
                        contractAddress,
                        web3,
                        dummyCredentials,
                        BigInteger.ZERO,
                        BigInteger.ZERO);

                BigInteger balance = erc20.balanceOf(address).send();
                BigInteger decimals = erc20.decimals().send();

                BigDecimal tokenBalance = new BigDecimal(balance)
                        .divide(new BigDecimal(10).pow(decimals.intValue()));

                return WalletBalance.of(tokenSymbol, tokenBalance); // FIXED
            }
        } catch (Exception e) {
            log.error("Failed to get balance for {} {}: ", tokenSymbol, address, e);
            return WalletBalance.zero(tokenSymbol);
        }
    }

    @Override
    public List<String> getSupportedTokens() {
        List<String> tokens = new ArrayList<>();
        tokens.add("ETH"); // Native token on Arbitrum
        tokens.addAll(getTokenContracts().keySet());

        // Add tokens from database
        tokens.addAll(getSupportedTokensFromDB().stream()
                .map(Token::getTokenSymbol)
                .collect(Collectors.toList()));

        return tokens;
    }

    @Override
    public NetworkConfig getNetworkConfig() {
        boolean isTestnet = networkConfig.isTestnetForNetwork("arbitrum");
        return NetworkConfig.builder()
                .networkName(networkConfig.getNetworkDisplayName("arbitrum"))
                .networkType("EVM")
                .nativeCurrency("ETH")
                .rpcUrl(networkConfig.getRpcUrl("arbitrum"))
                .explorerUrl(networkConfig.getExplorerUrl("arbitrum"))
                .chainId(networkConfig.getChainId("arbitrum"))
                .isTestnet(isTestnet)
                .averageFee(new BigDecimal(isTestnet ? "0.00001" : "0.0001")) // Very low fees on Arbitrum
                .confirmationBlocks(1) // Fast confirmations on Arbitrum
                .build();
    }

    @Override
    public boolean checkTransactionStatus(String txHash) {
        try {
            Web3j web3 = getWeb3j();
            EthGetTransactionReceipt receipt = web3.ethGetTransactionReceipt(txHash).send();

            return receipt.getTransactionReceipt().isPresent() &&
                    receipt.getTransactionReceipt().get().isStatusOK();
        } catch (Exception e) {
            log.error("Failed to check Arbitrum transaction status {}: ", txHash, e);
            return false;
        }
    }

    @Override
    public BigDecimal estimateFee(String tokenSymbol, BigDecimal amount) {
        try {
            Web3j web3 = getWeb3j();
            EthGasPrice gasPrice = web3.ethGasPrice().send();

            BigInteger gasLimit = "ETH".equals(tokenSymbol.toUpperCase()) ? this.gasLimit : this.erc20GasLimit;

            BigInteger totalGas = gasPrice.getGasPrice().multiply(gasLimit);
            return Convert.fromWei(totalGas.toString(), Convert.Unit.ETHER);
        } catch (Exception e) {
            log.error("Failed to estimate fee: ", e);
            return new BigDecimal("0.0001"); // Very low fees on Arbitrum
        }
    }

    @Override
    public boolean isValidAddress(String address) {
        try {
            // Arbitrum uses same address format as Ethereum
            return address != null &&
                    address.startsWith("0x") &&
                    address.length() == 42 &&
                    address.matches("^0x[a-fA-F0-9]{40}$");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getNetworkType() {
        return "EVM";
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

    private int getTokenDecimals(String tokenSymbol) { // NOW USED
        ContractInfo contractInfo = getTokenContracts().get(tokenSymbol.toUpperCase());
        if (contractInfo != null) {
            return contractInfo.decimals;
        }
        return 18; // Default for most ERC20 tokens
    }

    // FIXED GAS PRICE HANDLING
    private BigDecimal getTransactionFee(TransactionReceipt receipt) {
        try {
            BigInteger gasUsed = receipt.getGasUsed();

            // Handle gas price - getEffectiveGasPrice() returns String
            BigInteger gasPrice;
            String effectiveGasPriceStr = receipt.getEffectiveGasPrice();

            if (effectiveGasPriceStr != null && !effectiveGasPriceStr.isEmpty()) {
                // Remove "0x" prefix if present and convert hex to BigInteger
                if (effectiveGasPriceStr.startsWith("0x")) {
                    gasPrice = new BigInteger(effectiveGasPriceStr.substring(2), 16);
                } else {
                    gasPrice = new BigInteger(effectiveGasPriceStr);
                }
            } else {
                // Fallback to current gas price
                Web3j web3 = getWeb3j();
                gasPrice = web3.ethGasPrice().send().getGasPrice();
            }

            BigInteger totalFee = gasUsed.multiply(gasPrice);
            return Convert.fromWei(totalFee.toString(), Convert.Unit.ETHER);
        } catch (Exception e) {
            log.error("Failed to calculate Arbitrum transaction fee: ", e);
            return BigDecimal.ZERO;
        }
    }
}