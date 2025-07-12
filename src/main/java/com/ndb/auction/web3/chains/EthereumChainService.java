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
import com.ndb.auction.config.NetworkConfiguration;
import com.ndb.auction.dao.oracle.withdraw.TokenDao;
import com.ndb.auction.models.withdraw.Token;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EthereumChainService implements ChainService {

    @Value("${eth.json.rpc}")
    private String ETH_RPC_URL;

    @Value("${wallet.seed}")
    private String SEED_PHRASE;

    @Autowired
    private TokenDao tokenDao;

    @Autowired
    private NetworkConfiguration networkConfig;

    private Web3j web3j;
    private List<Token> supportedTokens;
    private final BigInteger gasLimit = BigInteger.valueOf(21000); // Standard ETH transfer
    private final BigInteger erc20GasLimit = BigInteger.valueOf(100000); // ERC20 transfer

    // Major ERC20 tokens on Ethereum mainnet
    private static final Map<String, ContractInfo> ERC20_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0xdAC17F958D2ee523a2206206994597C13D831ec7", 6, "Tether USD"),
            "USDC", new ContractInfo("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", 6, "USD Coin"),
            "DAI", new ContractInfo("0x6B175474E89094C44Da98b954EedeAC495271d0F", 18, "Dai Stablecoin"),
            "WBTC", new ContractInfo("0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599", 8, "Wrapped BTC"),
            "LINK", new ContractInfo("0x514910771AF9Ca656af840dff83E8264EcF986CA", 18, "ChainLink Token"),
            "UNI", new ContractInfo("0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984", 18, "Uniswap"),
            "AAVE", new ContractInfo("0x7Fc66500c84A76Ad7e9c93437bFc5Ac33E2DDaE9", 18, "Aave Token"),
            "CRV", new ContractInfo("0xD533a949740bb3306d119CC777fa900bA034cd52", 18, "Curve DAO Token"),
            "SUSHI", new ContractInfo("0x6B3595068778DD592e39A122f4f5a5cF09C90fE2", 18, "SushiToken"),
            "COMP", new ContractInfo("0xc00e94Cb662C3520282E6f5717214004A7f26888", 18, "Compound"));

    // Testnet contracts (Sepolia)
    private static final Map<String, ContractInfo> ERC20_TESTNET_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0x7169D38820dfd117C3FA1f22a697dBA58d90BA06", 6, "Tether USD"),
            "USDC", new ContractInfo("0x94a9D9AC8a22534E3FaCa9F4e7F2E2cf85d5E4C8", 6, "USD Coin"),
            "LINK", new ContractInfo("0x779877A7B0D9E8603169DdbD7836e478b4624789", 18, "ChainLink Token"));

    private Map<String, ContractInfo> getTokenContracts() {
        return networkConfig.isTestnetForNetwork("ethereum") ? ERC20_TESTNET_CONTRACTS : ERC20_CONTRACTS;
    }

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

    private Web3j getWeb3j() {
        if (web3j == null) {
            String rpcUrl = networkConfig.getRpcUrl("ethereum");
            web3j = Web3j.build(new HttpService(rpcUrl));
        }
        return web3j;
    }

    private List<Token> getSupportedTokensFromDB() {
        if (supportedTokens == null) {
            supportedTokens = tokenDao.selectAll().stream()
                    .filter(token -> "ERC20".equals(token.getNetwork()) || "ETHEREUM".equals(token.getNetwork()))
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
                // Native ETH transfer
                BigInteger weiAmount = Convert.toWei(amount, Convert.Unit.ETHER).toBigInteger();

                CompletableFuture<TransactionReceipt> receiptFuture = Transfer.sendFunds(
                        web3, credentials, toAddress, amount, Convert.Unit.ETHER).sendAsync();

                TransactionReceipt receipt = receiptFuture.get();

                return TransferResult.success(
                        receipt.getTransactionHash(),
                        getTransactionFee(receipt));
            } else {
                // ERC20 token transfer
                String contractAddress = getTokenContractAddress(tokenSymbol);
                if (contractAddress == null) {
                    return TransferResult.failed("Token not supported on Ethereum: " + tokenSymbol);
                }

                EthGasPrice gasPrice = web3.ethGasPrice().send();

                ERC20 erc20 = ERC20.load(
                        contractAddress,
                        web3,
                        credentials,
                        gasPrice.getGasPrice(),
                        erc20GasLimit);

                // Get token decimals and convert amount
                BigInteger decimals = erc20.decimals().send();
                BigInteger amountInSmallestUnit = amount.multiply(
                        new BigDecimal(10).pow(decimals.intValue())).toBigInteger();

                TransactionReceipt receipt = erc20.transfer(toAddress, amountInSmallestUnit).send();

                return TransferResult.success(
                        receipt.getTransactionHash(),
                        getTransactionFee(receipt));
            }
        } catch (Exception e) {
            log.error("Failed to send {} {} to {}: ", amount, tokenSymbol, toAddress, e);
            return TransferResult.failed("Transfer failed: " + e.getMessage());
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
                return WalletBalance.of(tokenSymbol, ethBalance);
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

                return WalletBalance.of(tokenSymbol, tokenBalance);
            }
        } catch (Exception e) {
            log.error("Failed to get balance for {} {}: ", tokenSymbol, address, e);
            return WalletBalance.zero(tokenSymbol);
        }
    }

    @Override
    public List<String> getSupportedTokens() {
        List<String> tokens = new ArrayList<>();
        tokens.add("ETH"); // Native token
        tokens.addAll(getTokenContracts().keySet());

        // Add tokens from database
        tokens.addAll(getSupportedTokensFromDB().stream()
                .map(Token::getTokenSymbol)
                .collect(Collectors.toList()));

        return tokens;
    }

    @Override
    public NetworkConfig getNetworkConfig() {
        boolean isTestnet = networkConfig.isTestnetForNetwork("ethereum");
        return NetworkConfig.builder()
                .networkName(networkConfig.getNetworkDisplayName("ethereum"))
                .networkType("ERC20")
                .nativeCurrency("ETH")
                .rpcUrl(networkConfig.getRpcUrl("ethereum"))
                .explorerUrl(networkConfig.getExplorerUrl("ethereum"))
                .chainId(networkConfig.getChainId("ethereum"))
                .isTestnet(isTestnet)
                .averageFee(new BigDecimal(isTestnet ? "0.001" : "20.0")) // Lower fees on testnet
                .confirmationBlocks(isTestnet ? 3 : 12) // Faster confirmations on testnet
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
            log.error("Failed to check transaction status {}: ", txHash, e);
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
            return new BigDecimal("0.005"); // Default fallback
        }
    }

    @Override
    public boolean isValidAddress(String address) {
        try {
            // Ethereum addresses are 42 characters long (including 0x prefix) and hex
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
        return "ERC20";
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