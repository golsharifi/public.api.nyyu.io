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
public class BscChainService implements ChainService {

    @Value("${bsc.json.rpc}")
    private String BSC_RPC_URL;

    @Value("${wallet.seed}")
    private String SEED_PHRASE;

    @Autowired
    private TokenDao tokenDao;

    @Autowired
    private NetworkConfiguration networkConfig;

    private Web3j web3j;
    private List<Token> supportedTokens;
    private final BigInteger gasLimit = BigInteger.valueOf(21000); // Standard BNB transfer
    private final BigInteger bep20GasLimit = BigInteger.valueOf(100000); // BEP20 transfer

    // Major BEP20 tokens on BSC mainnet
    private static final Map<String, ContractInfo> BEP20_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0x55d398326f99059fF775485246999027B3197955", 18, "Tether USD"),
            "USDC", new ContractInfo("0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", 18, "USD Coin"),
            "BUSD", new ContractInfo("0xe9e7CEA3DedcA5984780Bafc599bD69ADd087D56", 18, "Binance USD"),
            "DAI", new ContractInfo("0x1AF3F329e8BE154074D8769D1FFa4eE058B1DBc3", 18, "Dai Stablecoin"),
            "WBTC", new ContractInfo("0x7130d2A12B9BCbFAe4f2634d864A1Ee1Ce3Ead9c", 18, "Bitcoin BEP2"),
            "ETH", new ContractInfo("0x2170Ed0880ac9A755fd29B2688956BD959F933F8", 18, "Ethereum Token"),
            "ADA", new ContractInfo("0x3EE2200Efb3400fAbB9AacF31297cBdD1d435D47", 18, "Cardano Token"),
            "XRP", new ContractInfo("0x1D2F0da169ceB9fC7B3144628dB156f3F6c60dBE", 18, "XRP Token"),
            "DOT", new ContractInfo("0x7083609fCE4d1d8Dc0C979AAb8c869Ea2C873402", 18, "Polkadot Token"),
            "CAKE", new ContractInfo("0x0E09FaBB73Bd3Ade0a17ECC321fD13a19e81cE82", 18, "PancakeSwap Token"));

    // Testnet contracts (BSC Testnet)
    private static final Map<String, ContractInfo> BEP20_TESTNET_CONTRACTS = Map.of(
            "USDT", new ContractInfo("0x337610d27c682E347C9cD60BD4b3b107C9d34dDd", 18, "Tether USD"),
            "USDC", new ContractInfo("0x64544969ed7EBf5f083679233325356EbE738930", 18, "USD Coin"),
            "BUSD", new ContractInfo("0xeD24FC36d5Ee211Ea25A80239Fb8C4Cfd80f12Ee", 18, "Binance USD"));

    private Map<String, ContractInfo> getTokenContracts() {
        return networkConfig.isTestnetForNetwork("bsc") ? BEP20_TESTNET_CONTRACTS : BEP20_CONTRACTS;
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
            String rpcUrl = networkConfig.getRpcUrl("bsc");
            web3j = Web3j.build(new HttpService(rpcUrl));
        }
        return web3j;
    }

    private List<Token> getSupportedTokensFromDB() {
        if (supportedTokens == null) {
            supportedTokens = tokenDao.selectAll().stream()
                    .filter(token -> "BEP20".equals(token.getNetwork()) || "BSC".equals(token.getNetwork()))
                    .collect(Collectors.toList());
        }
        return supportedTokens;
    }

    @Override
    public TransferResult sendToken(String tokenSymbol, String fromPrivateKey, String toAddress, BigDecimal amount) {
        try {
            Web3j web3 = getWeb3j();
            Credentials credentials = Credentials.create(fromPrivateKey);

            if ("BNB".equals(tokenSymbol.toUpperCase())) {
                // Native BNB transfer
                BigInteger weiAmount = Convert.toWei(amount, Convert.Unit.ETHER).toBigInteger();

                CompletableFuture<TransactionReceipt> receiptFuture = Transfer.sendFunds(
                        web3, credentials, toAddress, amount, Convert.Unit.ETHER).sendAsync();

                TransactionReceipt receipt = receiptFuture.get();

                return TransferResult.success(
                        receipt.getTransactionHash(),
                        getTransactionFee(receipt));
            } else {
                // BEP20 token transfer
                String contractAddress = getTokenContractAddress(tokenSymbol);
                if (contractAddress == null) {
                    return TransferResult.failed("Token not supported on BSC: " + tokenSymbol);
                }

                EthGasPrice gasPrice = web3.ethGasPrice().send();

                ERC20 bep20 = ERC20.load(
                        contractAddress,
                        web3,
                        credentials,
                        gasPrice.getGasPrice(),
                        bep20GasLimit);

                // Get token decimals and convert amount
                BigInteger decimals = bep20.decimals().send();
                BigInteger amountInSmallestUnit = amount.multiply(
                        new BigDecimal(10).pow(decimals.intValue())).toBigInteger();

                TransactionReceipt receipt = bep20.transfer(toAddress, amountInSmallestUnit).send();

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

            if ("BNB".equals(tokenSymbol.toUpperCase())) {
                // Get BNB balance
                EthGetBalance ethGetBalance = web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
                BigDecimal bnbBalance = Convert.fromWei(ethGetBalance.getBalance().toString(), Convert.Unit.ETHER);
                return WalletBalance.of(tokenSymbol, bnbBalance);
            } else {
                // Get BEP20 token balance
                String contractAddress = getTokenContractAddress(tokenSymbol);
                if (contractAddress == null) {
                    return WalletBalance.zero(tokenSymbol);
                }

                // Create dummy credentials for read-only operations
                Credentials dummyCredentials = Credentials
                        .create("0x0000000000000000000000000000000000000000000000000000000000000001");

                ERC20 bep20 = ERC20.load(
                        contractAddress,
                        web3,
                        dummyCredentials,
                        BigInteger.ZERO,
                        BigInteger.ZERO);

                BigInteger balance = bep20.balanceOf(address).send();
                BigInteger decimals = bep20.decimals().send();

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
        tokens.add("BNB"); // Native token
        tokens.addAll(getTokenContracts().keySet());

        // Add tokens from database
        tokens.addAll(getSupportedTokensFromDB().stream()
                .map(Token::getTokenSymbol)
                .collect(Collectors.toList()));

        return tokens;
    }

    @Override
    public NetworkConfig getNetworkConfig() {
        boolean isTestnet = networkConfig.isTestnetForNetwork("bsc");
        return NetworkConfig.builder()
                .networkName(networkConfig.getNetworkDisplayName("bsc"))
                .networkType("BEP20")
                .nativeCurrency("BNB")
                .rpcUrl(networkConfig.getRpcUrl("bsc"))
                .explorerUrl(networkConfig.getExplorerUrl("bsc"))
                .chainId(networkConfig.getChainId("bsc"))
                .isTestnet(isTestnet)
                .averageFee(new BigDecimal(isTestnet ? "0.001" : "5.0")) // Lower fees on testnet
                .confirmationBlocks(isTestnet ? 3 : 3) // Same for BSC
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

            BigInteger gasLimit = "BNB".equals(tokenSymbol.toUpperCase()) ? this.gasLimit : this.bep20GasLimit;

            BigInteger totalGas = gasPrice.getGasPrice().multiply(gasLimit);
            return Convert.fromWei(totalGas.toString(), Convert.Unit.ETHER);
        } catch (Exception e) {
            log.error("Failed to estimate fee: ", e);
            return new BigDecimal("0.001"); // Default fallback (much lower than ETH)
        }
    }

    @Override
    public boolean isValidAddress(String address) {
        try {
            // BSC uses same address format as Ethereum
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
        return "BEP20";
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