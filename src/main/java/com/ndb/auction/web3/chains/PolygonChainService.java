package com.ndb.auction.web3.chains;

import com.ndb.auction.models.wallet.TransferResult;
import com.ndb.auction.models.wallet.WalletBalance;
import com.ndb.auction.models.wallet.NetworkConfig;

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
public class PolygonChainService implements ChainService {

    @Value("${polygon.json.rpc:https://polygon-rpc.com}")
    private String POLYGON_RPC_URL;

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

    // Major tokens on Polygon mainnet
    private static final Map<String, ContractInfo> POLYGON_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0xc2132d05d31c914a87c6611c10748aeb04b58e8f", 6, "Tether USD"),
            "USDC", new ContractInfo("0x2791bca1f2de4661ed88a30c99a7a9449aa84174", 6, "USD Coin"),
            "DAI", new ContractInfo("0x8f3cf7ad23cd3cadbd9735aff958023239c6a063", 18, "Dai Stablecoin"),
            "WETH", new ContractInfo("0x7ceb23fd6f88dd623c0ba2e8c8b4c2b9b4c30b14", 18, "Wrapped Ether"),
            "WBTC", new ContractInfo("0x1bfd67037b42cf73acf2047067bd4f2c47d9bfd6", 8, "Wrapped BTC"),
            "LINK", new ContractInfo("0x53e0bca35ec356bd5dddfebbd1fc0fd03fabad39", 18, "ChainLink Token"),
            "AAVE", new ContractInfo("0xd6df932a45c0f255f85145f286ea0b292b21c90b", 18, "Aave Token"),
            "CRV", new ContractInfo("0x172370d5cd63279efa6d502dab29171933a610af", 18, "Curve DAO Token"),
            "SUSHI", new ContractInfo("0x0b3f868e0be5597d5db7feb59e1cadbb0fdda50a", 18, "SushiToken"),
            "UNI", new ContractInfo("0xb33eaad8d922b1083446dc23f610c2567fb5180f", 18, "Uniswap"));

    // Testnet contracts (Mumbai)
    private static final Map<String, ContractInfo> POLYGON_TESTNET_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0x3813e82e6f7098b9583FC0F33a962D02018B6803", 6, "Tether USD"),
            "USDC", new ContractInfo("0x9999f7Fea5938fD3b1E26A12c3f2fb024e194f97", 6, "USD Coin"),
            "LINK", new ContractInfo("0x326C977E6efc84E512bB9C30f76E30c160eD06FB", 18, "ChainLink Token"),
            "WETH", new ContractInfo("0xA6FA4fB5f76172d178d61B04b0ecd319C5d1C0aa", 18, "Wrapped Ether"));

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
        return networkConfig.isTestnetForNetwork("polygon") ? POLYGON_TESTNET_CONTRACTS : POLYGON_CONTRACTS;
    }

    private Web3j getWeb3j() {
        if (web3j == null) {
            String rpcUrl = networkConfig.getRpcUrl("polygon");
            web3j = Web3j.build(new HttpService(rpcUrl));
        }
        return web3j;
    }

    private List<Token> getSupportedTokensFromDB() {
        if (supportedTokens == null) {
            supportedTokens = tokenDao.selectAll().stream()
                    .filter(token -> "POLYGON".equals(token.getNetwork()) || "MATIC".equals(token.getNetwork()))
                    .collect(Collectors.toList());
        }
        return supportedTokens;
    }

    @Override
    public TransferResult sendToken(String tokenSymbol, String fromPrivateKey, String toAddress, BigDecimal amount) {
        try {
            Web3j web3 = getWeb3j();
            Credentials credentials = Credentials.create(fromPrivateKey);

            if ("MATIC".equals(tokenSymbol.toUpperCase()) || "POLYGON".equals(tokenSymbol.toUpperCase())) {
                // Native MATIC transfer
                BigInteger weiAmount = Convert.toWei(amount, Convert.Unit.ETHER).toBigInteger();

                CompletableFuture<TransactionReceipt> receiptFuture = Transfer.sendFunds(
                        web3, credentials, toAddress, amount, Convert.Unit.ETHER).sendAsync();

                TransactionReceipt receipt = receiptFuture.get();

                return TransferResult.success(
                        receipt.getTransactionHash(),
                        getTransactionFee(receipt));
            } else {
                // ERC20 token transfer on Polygon
                String contractAddress = getTokenContractAddress(tokenSymbol);
                if (contractAddress == null) {
                    return TransferResult.failed("Token not supported on Polygon: " + tokenSymbol);
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
            log.error("Failed to send {} {} to {} on Polygon: ", amount, tokenSymbol, toAddress, e);
            return TransferResult.failed("Polygon transfer failed: " + e.getMessage());
        }
    }

    @Override
    public WalletBalance getBalance(String address, String tokenSymbol) {
        try {
            Web3j web3 = getWeb3j();

            if ("MATIC".equals(tokenSymbol.toUpperCase()) || "POLYGON".equals(tokenSymbol.toUpperCase())) {
                // Get MATIC balance
                EthGetBalance ethGetBalance = web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
                BigDecimal maticBalance = Convert.fromWei(ethGetBalance.getBalance().toString(), Convert.Unit.ETHER);
                return WalletBalance.of(tokenSymbol, maticBalance);
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
        tokens.add("MATIC"); // Native token
        tokens.addAll(getTokenContracts().keySet());

        // Add tokens from database
        tokens.addAll(getSupportedTokensFromDB().stream()
                .map(Token::getTokenSymbol)
                .collect(Collectors.toList()));

        return tokens;
    }

    @Override
    public NetworkConfig getNetworkConfig() {
        boolean isTestnet = networkConfig.isTestnetForNetwork("polygon");
        return NetworkConfig.builder()
                .networkName(networkConfig.getNetworkDisplayName("polygon"))
                .networkType("EVM")
                .nativeCurrency("MATIC")
                .rpcUrl(networkConfig.getRpcUrl("polygon"))
                .explorerUrl(networkConfig.getExplorerUrl("polygon"))
                .chainId(networkConfig.getChainId("polygon"))
                .isTestnet(isTestnet)
                .averageFee(new BigDecimal(isTestnet ? "0.0001" : "0.001")) // Lower fees on testnet
                .confirmationBlocks(isTestnet ? 5 : 10)
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
            log.error("Failed to check Polygon transaction status {}: ", txHash, e);
            return false;
        }
    }

    @Override
    public BigDecimal estimateFee(String tokenSymbol, BigDecimal amount) {
        try {
            Web3j web3 = getWeb3j();
            EthGasPrice gasPrice = web3.ethGasPrice().send();

            BigInteger gasLimit = "MATIC".equals(tokenSymbol.toUpperCase()) ? this.gasLimit : this.erc20GasLimit;

            BigInteger totalGas = gasPrice.getGasPrice().multiply(gasLimit);
            return Convert.fromWei(totalGas.toString(), Convert.Unit.ETHER);
        } catch (Exception e) {
            log.error("Failed to estimate Polygon fee for {}: ", tokenSymbol, e);
            return new BigDecimal("0.001"); // Default estimate
        }
    }

    @Override
    public boolean isValidAddress(String address) {
        try {
            // Polygon uses same address format as Ethereum
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

    private int getTokenDecimals(String tokenSymbol) {
        ContractInfo contractInfo = getTokenContracts().get(tokenSymbol.toUpperCase());
        if (contractInfo != null) {
            return contractInfo.decimals;
        }
        return 18; // Default for most ERC20 tokens
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