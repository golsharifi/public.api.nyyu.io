package com.ndb.auction.web3;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Type;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.MnemonicUtils;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.Transfer;
import org.web3j.utils.Convert;
import org.web3j.utils.Convert.Unit;

import com.ndb.auction.dao.oracle.withdraw.TokenDao;
import com.ndb.auction.exceptions.UserNotFoundException;
import com.ndb.auction.models.withdraw.Token;
import com.ndb.auction.models.wallet.TransferResult;
import com.ndb.auction.payload.NetworkMetadata;
import com.ndb.auction.web3.chains.*;

import lombok.extern.slf4j.Slf4j;

/**
 * UPDATED WithdrawWalletService with Multi-Chain Support
 * 
 * Supported Networks:
 * - Ethereum (ETH + ERC20 tokens)
 * - BSC (BNB + BEP20 tokens)
 * - Polygon (MATIC + Polygon tokens)
 * - TRON (TRX + TRC20 tokens)
 * - Solana (SOL + SPL tokens)
 * - Avalanche (AVAX + tokens)
 * - Arbitrum (ETH + tokens)
 * - Bitcoin (BTC)
 */
@Service
@Slf4j
public class WithdrawWalletService {

    @Value("${wallet.seed}")
    private String SEED_PHRASE;

    // JSON RPC URLs
    @Value("${bsc.json.rpc}")
    private String BSC_JSON_RPC;

    @Value("${eth.json.rpc}")
    private String ETH_JSON_RPC;

    @Value("${polygon.json.rpc:https://polygon-rpc.com}")
    private String POLYGON_JSON_RPC;

    @Value("${avalanche.json.rpc:https://api.avax.network/ext/bc/C/rpc}")
    private String AVALANCHE_JSON_RPC;

    @Value("${arbitrum.json.rpc:https://arb1.arbitrum.io/rpc}")
    private String ARBITRUM_JSON_RPC;

    private Map<String, NetworkMetadata> networkMetadataMap;

    // web3 instances
    private final BigInteger gasLimit = new BigInteger("80000");

    // Chain services for new networks
    @Autowired
    private TronChainService tronChainService;

    @Autowired
    private PolygonChainService polygonChainService;

    @Autowired
    private SolanaChainService solanaChainService;

    @Autowired
    private AvalancheChainService avalancheChainService;

    @Autowired
    private ArbitrumChainService arbitrumChainService;

    // NEW CHAIN SERVICES
    @Autowired
    private EthereumChainService ethereumChainService;

    @Autowired
    private BscChainService bscChainService;

    @Autowired
    private BitcoinChainService bitcoinChainService;

    // token address for each network
    private List<Token> tokenList;

    // singleton
    @Autowired
    private TokenDao tokenDao;

    @Autowired
    private MessageSource messageSource;

    private synchronized void fillTokenMap() {
        tokenList = tokenDao.selectAll();
    }

    public static <T> Collector<T, ?, T> toSingleton() {
        return Collectors.collectingAndThen(
                Collectors.toList(),
                list -> {
                    if (list.size() != 1) {
                        throw new IllegalStateException();
                    }
                    return list.get(0);
                });
    }

    @PostConstruct
    public void init() {
        var networkList = new ArrayList<NetworkMetadata>();

        // EVM Networks
        networkList.add(NetworkMetadata.builder()
                .network("ERC20")
                .jsonRpc(ETH_JSON_RPC)
                .build());
        networkList.add(NetworkMetadata.builder()
                .network("BEP20")
                .jsonRpc(BSC_JSON_RPC)
                .build());
        networkList.add(NetworkMetadata.builder()
                .network("POLYGON")
                .jsonRpc(POLYGON_JSON_RPC)
                .build());
        networkList.add(NetworkMetadata.builder()
                .network("AVALANCHE")
                .jsonRpc(AVALANCHE_JSON_RPC)
                .build());
        networkList.add(NetworkMetadata.builder()
                .network("ARBITRUM")
                .jsonRpc(ARBITRUM_JSON_RPC)
                .build());

        networkMetadataMap = networkList.stream()
                .collect(Collectors.toMap(NetworkMetadata::getNetwork, Function.identity()));
    }

    // ===== MAIN WITHDRAWAL METHOD - UPDATED TO SUPPORT ALL NETWORKS =====

    /**
     * Universal token withdrawal method supporting all networks
     * 
     * @param network     Network name (ERC20, BEP20, POLYGON, TRC20, SOL,
     *                    AVALANCHE, ARBITRUM, BTC)
     * @param tokenSymbol Token symbol (ETH, BNB, USDT, etc.)
     * @param address     Destination address
     * @param amount      Amount to withdraw
     * @return Transaction hash or "Failed"
     */
    public String withdrawToken(String network, String tokenSymbol, String address, double amount) {
        try {
            log.info("Withdrawing {} {} on {} to {}", amount, tokenSymbol, network, address);

            // Normalize network name
            String normalizedNetwork = normalizeNetworkName(network);
            BigDecimal withdrawAmount = BigDecimal.valueOf(amount);

            // Route to appropriate chain service based on network
            switch (normalizedNetwork) {
                case "ERC20":
                    return handleEthereumWithdrawal(tokenSymbol, address, withdrawAmount);

                case "BEP20":
                    return handleBscWithdrawal(tokenSymbol, address, withdrawAmount);

                case "POLYGON":
                    return handlePolygonWithdrawal(tokenSymbol, address, withdrawAmount);

                case "TRC20":
                case "TRON":
                    return handleTronWithdrawal(tokenSymbol, address, withdrawAmount);

                case "SOL":
                case "SOLANA":
                    return handleSolanaWithdrawal(tokenSymbol, address, withdrawAmount);

                case "AVALANCHE":
                case "AVAX":
                    return handleAvalancheWithdrawal(tokenSymbol, address, withdrawAmount);

                case "ARBITRUM":
                case "ARB":
                    return handleArbitrumWithdrawal(tokenSymbol, address, withdrawAmount);

                case "BTC":
                case "BITCOIN":
                    return handleBitcoinWithdrawal(tokenSymbol, address, withdrawAmount);

                default:
                    log.error("Unsupported network: {}", network);
                    return "Failed";
            }

        } catch (Exception e) {
            log.error("Error in withdrawToken for {} {} on {}: ", amount, tokenSymbol, network, e);
            return "Failed";
        }
    }

    // ===== EXISTING EVM NETWORK HANDLERS (ETH/BSC) - KEEP YOUR ORIGINAL LOGIC
    // =====

    private String handleEthereumWithdrawal(String tokenSymbol, String address, BigDecimal amount) {
        try {
            String privateKey = getNetworkPrivateKey("ETHEREUM");
            TransferResult result = ethereumChainService.sendToken(tokenSymbol, privateKey, address, amount);
            return result.isSuccess() ? result.getTransactionHash() : "Failed";
        } catch (Exception e) {
            log.error("Failed Ethereum withdrawal: ", e);
            return "Failed";
        }
    }

    private String handleBscWithdrawal(String tokenSymbol, String address, BigDecimal amount) {
        try {
            String privateKey = getNetworkPrivateKey("BSC");
            TransferResult result = bscChainService.sendToken(tokenSymbol, privateKey, address, amount);
            return result.isSuccess() ? result.getTransactionHash() : "Failed";
        } catch (Exception e) {
            log.error("Failed BSC withdrawal: ", e);
            return "Failed";
        }
    }

    private String handleEvmWithdrawal(String network, String tokenSymbol, String address, BigDecimal amount) {
        try {
            if (tokenList == null || tokenList.size() == 0) {
                fillTokenMap();
            }

            var netMetadata = networkMetadataMap.get(network);
            if (netMetadata == null) {
                String msg = messageSource.getMessage("no_network", null, Locale.ENGLISH);
                throw new UserNotFoundException(msg, "network");
            }

            Web3j web3 = Web3j.build(new HttpService(netMetadata.getJsonRpc()));

            int[] derivationPath = { 44 | Bip32ECKeyPair.HARDENED_BIT, 60 | Bip32ECKeyPair.HARDENED_BIT,
                    0 | Bip32ECKeyPair.HARDENED_BIT, 0, 0 };
            Bip32ECKeyPair masterKeypair = Bip32ECKeyPair
                    .generateKeyPair(MnemonicUtils.generateSeed(SEED_PHRASE, null));
            Bip32ECKeyPair derivedKeyPair = Bip32ECKeyPair.deriveKeyPair(masterKeypair, derivationPath);
            Credentials credentials = Credentials.create(derivedKeyPair);

            if (network.equals("ERC20") && tokenSymbol.equals("ETH")) {
                var bAmount = Convert.toWei(amount.toString(), Convert.Unit.ETHER);
                TransactionReceipt txnReceipt = Transfer.sendFunds(
                        web3, credentials, address, bAmount, Convert.Unit.WEI).sendAsync().get();
                return txnReceipt.getTransactionHash();
            } else if (network.equals("BEP20") && tokenSymbol.equals("BNB")) {
                var bAmount = Convert.toWei(amount.toString(), Convert.Unit.ETHER);
                TransactionReceipt txnReceipt = Transfer.sendFunds(
                        web3, credentials, address, bAmount, Convert.Unit.WEI).sendAsync().get();
                return txnReceipt.getTransactionHash();
            } else {
                Token tokenMetadata = tokenList.stream()
                        .filter(token -> token.getNetwork().equals(network)
                                && token.getTokenSymbol().equals(tokenSymbol))
                        .collect(toSingleton());

                var _gasPrice = web3.ethGasPrice().send().getGasPrice();

                @SuppressWarnings("deprecation")
                ERC20 erc20 = ERC20.load(
                        tokenMetadata.getAddress(),
                        web3,
                        credentials,
                        _gasPrice,
                        gasLimit);
                var decimals = erc20.decimals().send();
                var doubleAmount = amount.doubleValue() * Math.pow(10, decimals.intValue());
                var bAmount = BigDecimal.valueOf(doubleAmount).toBigInteger();

                log.info("decimals: " + decimals.toString());
                log.info("amount: " + amount.toString());
                log.info("b amount: " + bAmount.toString());

                TransactionReceipt receipt = erc20.transfer(address, bAmount).send();
                return receipt.getTransactionHash();
            }
        } catch (Exception e) {
            log.error("Failed EVM withdrawal on {}: ", network, e);
            return "Failed";
        }
    }

    // ===== NEW CHAIN HANDLERS USING CHAIN SERVICES =====

    private String handlePolygonWithdrawal(String tokenSymbol, String address, BigDecimal amount) {
        try {
            String privateKey = getNetworkPrivateKey("POLYGON");
            TransferResult result = polygonChainService.sendToken(tokenSymbol, privateKey, address, amount);
            return result.isSuccess() ? result.getTransactionHash() : "Failed";
        } catch (Exception e) {
            log.error("Failed Polygon withdrawal: ", e);
            return "Failed";
        }
    }

    private String handleTronWithdrawal(String tokenSymbol, String address, BigDecimal amount) {
        try {
            String privateKey = getNetworkPrivateKey("TRON");
            TransferResult result = tronChainService.sendToken(tokenSymbol, privateKey, address, amount);
            return result.isSuccess() ? result.getTransactionHash() : "Failed";
        } catch (Exception e) {
            log.error("Failed TRON withdrawal: ", e);
            return "Failed";
        }
    }

    private String handleSolanaWithdrawal(String tokenSymbol, String address, BigDecimal amount) {
        try {
            String privateKey = getNetworkPrivateKey("SOLANA");
            TransferResult result = solanaChainService.sendToken(tokenSymbol, privateKey, address, amount);
            return result.isSuccess() ? result.getTransactionHash() : "Failed";
        } catch (Exception e) {
            log.error("Failed Solana withdrawal: ", e);
            return "Failed";
        }
    }

    private String handleAvalancheWithdrawal(String tokenSymbol, String address, BigDecimal amount) {
        try {
            String privateKey = getNetworkPrivateKey("AVALANCHE");
            TransferResult result = avalancheChainService.sendToken(tokenSymbol, privateKey, address, amount);
            return result.isSuccess() ? result.getTransactionHash() : "Failed";
        } catch (Exception e) {
            log.error("Failed Avalanche withdrawal: ", e);
            return "Failed";
        }
    }

    private String handleArbitrumWithdrawal(String tokenSymbol, String address, BigDecimal amount) {
        try {
            String privateKey = getNetworkPrivateKey("ARBITRUM");
            TransferResult result = arbitrumChainService.sendToken(tokenSymbol, privateKey, address, amount);
            return result.isSuccess() ? result.getTransactionHash() : "Failed";
        } catch (Exception e) {
            log.error("Failed Arbitrum withdrawal: ", e);
            return "Failed";
        }
    }

    private String handleBitcoinWithdrawal(String tokenSymbol, String address, BigDecimal amount) {
        try {
            if (!"BTC".equals(tokenSymbol.toUpperCase())) {
                return "Failed";
            }

            String privateKey = getNetworkPrivateKey("BITCOIN");
            TransferResult result = bitcoinChainService.sendToken(tokenSymbol, privateKey, address, amount);
            return result.isSuccess() ? result.getTransactionHash() : "Failed";
        } catch (Exception e) {
            log.error("Failed Bitcoin withdrawal: ", e);
            return "Failed";
        }
    }

    // ===== EXISTING METHODS - KEEP YOUR ORIGINAL IMPLEMENTATIONS =====

    public double getBalance(String network, String tokenSymbol) {
        try {
            // bitcoin processing
            if (network.equals("BTC") && tokenSymbol.equals("BTC")) {
                NetworkParameters params = MainNetParams.get();
                var seed = new DeterministicSeed(SEED_PHRASE, null, "", 1409478661L);
                Wallet wallet = Wallet.fromSeed(params, seed, Script.ScriptType.P2WPKH);
                var addr = wallet.currentReceiveAddress();
                log.info(addr.toString());
                log.info(wallet.getBalance().toPlainString());
                return 0.0;
            }

            if (tokenList == null || tokenList.size() == 0)
                fillTokenMap();
            var netMetadata = networkMetadataMap.get(network);
            if (netMetadata == null) {
                String msg = messageSource.getMessage("no_network", null, Locale.ENGLISH);
                throw new UserNotFoundException(msg, "network");
            }
            Web3j web3 = Web3j.build(new HttpService(netMetadata.getJsonRpc()));

            int[] derivationPath = { 44 | Bip32ECKeyPair.HARDENED_BIT, 60 | Bip32ECKeyPair.HARDENED_BIT,
                    0 | Bip32ECKeyPair.HARDENED_BIT, 0, 0 };
            Bip32ECKeyPair masterKeypair = Bip32ECKeyPair
                    .generateKeyPair(MnemonicUtils.generateSeed(SEED_PHRASE, null));
            Bip32ECKeyPair derivedKeyPair = Bip32ECKeyPair.deriveKeyPair(masterKeypair, derivationPath);
            Credentials credentials = Credentials.create(derivedKeyPair);

            // get balance
            if (network.equals("ERC20") && tokenSymbol.equals("ETH")) {
                EthGetBalance ethBalance = web3
                        .ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST)
                        .sendAsync()
                        .get();
                var weiBalance = ethBalance.getBalance();
                return Convert.fromWei(weiBalance.toString(), Unit.ETHER).doubleValue();
            } else if (network.equals("BEP20") && tokenSymbol.equals("BNB")) {
                EthGetBalance ethBalance = web3
                        .ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST)
                        .sendAsync()
                        .get();
                var weiBalance = ethBalance.getBalance();
                return Convert.fromWei(weiBalance.toString(), Unit.ETHER).doubleValue();
            } else {
                var tokenMetadata = tokenList.stream()
                        .filter(token -> token.getNetwork().equals(network)
                                && token.getTokenSymbol().equals(tokenSymbol))
                        .collect(toSingleton());
                var _gasPrice = web3.ethGasPrice().send().getGasPrice();
                @SuppressWarnings("deprecation")
                ERC20 erc20 = ERC20.load(
                        tokenMetadata.getAddress(),
                        web3,
                        credentials,
                        _gasPrice,
                        gasLimit);
                var balance = erc20.balanceOf(credentials.getAddress()).sendAsync().get();
                return Convert.fromWei(balance.toString(), Unit.ETHER).doubleValue();
            }
        } catch (Exception e) {
            String msg = messageSource.getMessage("unknown", null, Locale.ENGLISH);
            throw new UserNotFoundException(msg, "network");
        }
    }

    public String cancelTransaction(int nonce) {
        String network = "ERC20";

        try {
            var netMetadata = networkMetadataMap.get(network);
            if (netMetadata == null) {
                String msg = messageSource.getMessage("no_network", null, Locale.ENGLISH);
                throw new UserNotFoundException(msg, "network");
            }
            Web3j web3 = Web3j.build(new HttpService(netMetadata.getJsonRpc()));

            int[] derivationPath = { 44 | Bip32ECKeyPair.HARDENED_BIT, 60 | Bip32ECKeyPair.HARDENED_BIT,
                    0 | Bip32ECKeyPair.HARDENED_BIT, 0, 0 };
            Bip32ECKeyPair masterKeypair = Bip32ECKeyPair
                    .generateKeyPair(MnemonicUtils.generateSeed(SEED_PHRASE, null));
            Bip32ECKeyPair derivedKeyPair = Bip32ECKeyPair.deriveKeyPair(masterKeypair, derivationPath);
            Credentials credentials = Credentials.create(derivedKeyPair);

            if (!network.equals("ERC20") && !network.equals("BEP20")) {
                return "Failed";
            }

            var _gasPrice = web3.ethGasPrice().send().getGasPrice();

            final var function = new org.web3j.abi.datatypes.Function(
                    "transfer",
                    Arrays.<Type>asList(
                            new org.web3j.abi.datatypes.Address("0x075b2d512024315034Df97439379E7D794D45099"),
                            new org.web3j.abi.datatypes.generated.Uint256(0)),
                    Collections.<TypeReference<?>>emptyList());
            String encodedFunction = FunctionEncoder.encode(function);

            RawTransaction rawTransaction = RawTransaction.createTransaction(BigInteger.valueOf(nonce), _gasPrice,
                    gasLimit, "0x075b2d512024315034Df97439379E7D794D45099", BigInteger.ZERO, encodedFunction);
            var transactionManager = new RawTransactionManager(web3, credentials);
            var txn = transactionManager.signAndSend(rawTransaction);

            log.info("hash: {}", txn.getTransactionHash());

            return txn.getTransactionHash();
        } catch (Exception e) {
            e.printStackTrace();
            return "error";
        }
    }

    // ===== HELPER METHODS =====

    /**
     * Normalize network names for consistency
     */
    private String normalizeNetworkName(String network) {
        if (network == null)
            return "";

        switch (network.toUpperCase()) {
            case "ETHEREUM":
            case "ETH":
                return "ERC20";
            case "BINANCE":
            case "BSC":
            case "BNB":
                return "BEP20";
            case "MATIC":
            case "POLY":
                return "POLYGON";
            case "TRON":
            case "TRX":
                return "TRC20";
            case "SOLANA":
            case "SPL":
                return "SOL";
            case "AVAX":
                return "AVALANCHE";
            case "ARB":
                return "ARBITRUM";
            case "BITCOIN":
                return "BTC";
            default:
                return network.toUpperCase();
        }
    }

    /**
     * Get private key for a specific network
     * You'll need to implement this based on your key management strategy
     */
    private String getNetworkPrivateKey(String network) {
        try {
            // For now, derive from seed phrase - you may want different keys per network
            int[] derivationPath = { 44 | Bip32ECKeyPair.HARDENED_BIT, 60 | Bip32ECKeyPair.HARDENED_BIT,
                    0 | Bip32ECKeyPair.HARDENED_BIT, 0, 0 };
            Bip32ECKeyPair masterKeypair = Bip32ECKeyPair
                    .generateKeyPair(MnemonicUtils.generateSeed(SEED_PHRASE, null));
            Bip32ECKeyPair derivedKeyPair = Bip32ECKeyPair.deriveKeyPair(masterKeypair, derivationPath);

            // For TRON and Solana, you might need different derivation or conversion
            return derivedKeyPair.getPrivateKey().toString(16);
        } catch (Exception e) {
            log.error("Failed to get private key for network {}: ", network, e);
            throw new RuntimeException("Failed to get private key for network: " + network, e);
        }
    }

    /**
     * Get list of supported networks
     */
    public List<String> getSupportedNetworks() {
        return Arrays.asList(
                "ERC20", "BEP20", "POLYGON", "TRC20", "SOL",
                "AVALANCHE", "ARBITRUM", "BTC");
    }

    /**
     * Check if a network is supported
     */
    public boolean isNetworkSupported(String network) {
        String normalized = normalizeNetworkName(network);
        return getSupportedNetworks().contains(normalized);
    }
}