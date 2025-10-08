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
public class AvalancheChainService implements ChainService {

    @Value("${avalanche.json.rpc:https://api.avax.network/ext/bc/C/rpc}")
    private String AVALANCHE_RPC_URL;

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

    // Major tokens on Avalanche C-Chain mainnet
    private static final Map<String, ContractInfo> AVALANCHE_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7", 6, "Tether USD"),
            "USDC", new ContractInfo("0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E", 6, "USD Coin"),
            "DAI", new ContractInfo("0xd586E7F844cEa2F87f50152665BCbc2C279D8d70", 18, "Dai Stablecoin"),
            "WETH", new ContractInfo("0x49D5c2BdFfac6CE2BFdB6640F4F80f226bc10bAB", 18, "Wrapped Ether"),
            "WBTC", new ContractInfo("0x50b7545627a5162F82A992c33b87aDc75187B218", 8, "Wrapped BTC"),
            "LINK", new ContractInfo("0x5947BB275c521040051D82396192181b413227A3", 18, "ChainLink Token"),
            "UNI", new ContractInfo("0x8eBAf22B6F053dFFeaf46f4Dd9eFA95D89ba8580", 18, "Uniswap"),
            "AAVE", new ContractInfo("0x63a72806098Bd3D9520cC43356dD78afe5D386D9", 18, "Aave Token"),
            "JOE", new ContractInfo("0x6e84a6216eA6dACC71eE8E6b0a5B7322EEbC0fDd", 18, "JoeToken"),
            "PNG", new ContractInfo("0x60781C2586D68229fde47564546784ab3fACA982", 18, "Pangolin"));

    // Testnet contracts (Fuji)
    private static final Map<String, ContractInfo> AVALANCHE_TESTNET_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0x9999f7fea5938fd3b1e26a12c3f2fb024e194f97", 6, "Tether USD"),
            "USDC", new ContractInfo("0x5425890298aed601595a70AB815c96711a31Bc65", 6, "USD Coin"),
            "LINK", new ContractInfo("0x0b9d5D9136855f6FEc3c0993feE6E9CE8a297846", 18, "ChainLink Token"),
            "WETH", new ContractInfo("0x9c3C9283D3e44854697Cd22D3Faa240Cfb032889", 18, "Wrapped Ether"));

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
        return networkConfig.isTestnetForNetwork("avalanche") ? AVALANCHE_TESTNET_CONTRACTS : AVALANCHE_CONTRACTS;
    }

    private Web3j getWeb3j() {
        if (web3j == null) {
            String rpcUrl = networkConfig.getRpcUrl("avalanche");
            web3j = Web3j.build(new HttpService(rpcUrl));
        }
        return web3j;
    }

    private List<Token> getSupportedTokensFromDB() {
        if (supportedTokens == null) {
            supportedTokens = tokenDao.selectAll().stream()
                    .filter(token -> "AVALANCHE".equals(token.getNetwork()) || "AVAX".equals(token.getNetwork()))
                    .collect(Collectors.toList());
        }
        return supportedTokens;
    }

    @Override
    public TransferResult sendToken(String tokenSymbol, String fromPrivateKey, String toAddress, BigDecimal amount) {
        try {
            Web3j web3 = getWeb3j();
            Credentials credentials = Credentials.create(fromPrivateKey);

            if ("AVAX".equals(tokenSymbol.toUpperCase()) || "AVALANCHE".equals(tokenSymbol.toUpperCase())) {
                // Native AVAX transfer
                BigInteger weiAmount = Convert.toWei(amount, Convert.Unit.ETHER).toBigInteger();

                CompletableFuture<TransactionReceipt> receiptFuture = Transfer.sendFunds(
                        web3, credentials, toAddress, amount, Convert.Unit.ETHER).sendAsync();

                TransactionReceipt receipt = receiptFuture.get();

                return TransferResult.success(
                        receipt.getTransactionHash(),
                        getTransactionFee(receipt));
            } else {
                // ERC20 token transfer on Avalanche
                String contractAddress = getTokenContractAddress(tokenSymbol);
                if (contractAddress == null) {
                    return TransferResult.failed("Token not supported on Avalanche: " + tokenSymbol);
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
            log.error("Failed to send {} {} to {} on Avalanche: ", amount, tokenSymbol, toAddress, e);
            return TransferResult.failed("Avalanche transfer failed: " + e.getMessage());
        }
    }

    @Override
    public WalletBalance getBalance(String address, String tokenSymbol) {
        try {
            Web3j web3 = getWeb3j();

            if ("AVAX".equals(tokenSymbol.toUpperCase()) || "AVALANCHE".equals(tokenSymbol.toUpperCase())) {
                // Get AVAX balance
                EthGetBalance ethGetBalance = web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
                BigDecimal avaxBalance = Convert.fromWei(ethGetBalance.getBalance().toString(), Convert.Unit.ETHER);
                return WalletBalance.of(tokenSymbol, avaxBalance);
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
        tokens.add("AVAX"); // Native token
        tokens.addAll(getTokenContracts().keySet());

        // Add tokens from database
        tokens.addAll(getSupportedTokensFromDB().stream()
                .map(Token::getTokenSymbol)
                .collect(Collectors.toList()));

        return tokens;
    }

    @Override
    public NetworkConfig getNetworkConfig() {
        boolean isTestnet = networkConfig.isTestnetForNetwork("avalanche");
        return NetworkConfig.builder()
                .networkName(networkConfig.getNetworkDisplayName("avalanche"))
                .networkType("EVM")
                .nativeCurrency("AVAX")
                .rpcUrl(networkConfig.getRpcUrl("avalanche"))
                .explorerUrl(networkConfig.getExplorerUrl("avalanche"))
                .chainId(networkConfig.getChainId("avalanche"))
                .isTestnet(isTestnet)
                .averageFee(new BigDecimal(isTestnet ? "0.001" : "0.01")) // Lower fees on testnet
                .confirmationBlocks(isTestnet ? 3 : 5)
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
            log.error("Failed to check Avalanche transaction status {}: ", txHash, e);
            return false;
        }
    }

    @Override
    public BigDecimal estimateFee(String tokenSymbol, BigDecimal amount) {
        try {
            Web3j web3 = getWeb3j();
            EthGasPrice gasPrice = web3.ethGasPrice().send();

            BigInteger gasLimit = "AVAX".equals(tokenSymbol.toUpperCase()) ? this.gasLimit : this.erc20GasLimit;

            BigInteger totalGas = gasPrice.getGasPrice().multiply(gasLimit);
            return Convert.fromWei(totalGas.toString(), Convert.Unit.ETHER);
        } catch (Exception e) {
            log.error("Failed to estimate fee: ", e);
            return new BigDecimal("0.01"); // Default fallback
        }
    }

    @Override
    public boolean isValidAddress(String address) {
        try {
            // Avalanche uses same address format as Ethereum
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