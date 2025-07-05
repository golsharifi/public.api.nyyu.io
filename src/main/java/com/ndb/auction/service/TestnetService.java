package com.ndb.auction.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import com.ndb.auction.config.NetworkConfiguration;
import com.ndb.auction.web3.chains.*;

import java.util.*;

@Service
@Slf4j
public class TestnetService {

    @Autowired
    private NetworkConfiguration networkConfig;

    @Autowired
    private EthereumChainService ethereumChainService;

    @Autowired
    private BscChainService bscChainService;

    @Autowired
    private PolygonChainService polygonChainService;

    @Autowired
    private AvalancheChainService avalancheChainService;

    @Autowired
    private ArbitrumChainService arbitrumChainService;

    @Autowired
    private TronChainService tronChainService;

    @Autowired
    private SolanaChainService solanaChainService;

    @Autowired
    private BitcoinChainService bitcoinChainService;

    /**
     * Get testnet status for all networks
     */
    public Map<String, Boolean> getTestnetStatus() {
        Map<String, Boolean> status = new HashMap<>();

        List<String> networks = Arrays.asList(
                "ethereum", "bsc", "polygon", "avalanche",
                "arbitrum", "tron", "solana", "bitcoin");

        for (String network : networks) {
            status.put(network, networkConfig.isTestnetForNetwork(network));
        }

        return status;
    }

    /**
     * Get testnet faucet URLs for getting test tokens
     */
    public Map<String, String> getTestnetFaucets() {
        Map<String, String> faucets = new HashMap<>();

        faucets.put("ethereum", "https://sepoliafaucet.com/");
        faucets.put("bsc", "https://testnet.binance.org/faucet-smart");
        faucets.put("polygon", "https://faucet.polygon.technology/");
        faucets.put("avalanche", "https://faucet.avax.network/");
        faucets.put("arbitrum", "https://faucet.triangleplatform.com/arbitrum/sepolia");
        faucets.put("tron", "https://nileex.io/join/getJoinPage");
        faucets.put("solana", "https://faucet.solana.com/");
        faucets.put("bitcoin", "https://coinfaucet.eu/en/btc-testnet/");

        return faucets;
    }

    /**
     * Get network information including testnet status
     */
    public Map<String, NetworkInfo> getNetworkInfo() {
        Map<String, NetworkInfo> networkInfo = new HashMap<>();

        networkInfo.put("ethereum", NetworkInfo.builder()
                .name(networkConfig.getNetworkDisplayName("ethereum"))
                .isTestnet(networkConfig.isTestnetForNetwork("ethereum"))
                .rpcUrl(networkConfig.getRpcUrl("ethereum"))
                .explorerUrl(networkConfig.getExplorerUrl("ethereum"))
                .chainId(networkConfig.getChainId("ethereum"))
                .nativeCurrency("ETH")
                .build());

        networkInfo.put("bsc", NetworkInfo.builder()
                .name(networkConfig.getNetworkDisplayName("bsc"))
                .isTestnet(networkConfig.isTestnetForNetwork("bsc"))
                .rpcUrl(networkConfig.getRpcUrl("bsc"))
                .explorerUrl(networkConfig.getExplorerUrl("bsc"))
                .chainId(networkConfig.getChainId("bsc"))
                .nativeCurrency("BNB")
                .build());

        networkInfo.put("polygon", NetworkInfo.builder()
                .name(networkConfig.getNetworkDisplayName("polygon"))
                .isTestnet(networkConfig.isTestnetForNetwork("polygon"))
                .rpcUrl(networkConfig.getRpcUrl("polygon"))
                .explorerUrl(networkConfig.getExplorerUrl("polygon"))
                .chainId(networkConfig.getChainId("polygon"))
                .nativeCurrency("MATIC")
                .build());

        networkInfo.put("avalanche", NetworkInfo.builder()
                .name(networkConfig.getNetworkDisplayName("avalanche"))
                .isTestnet(networkConfig.isTestnetForNetwork("avalanche"))
                .rpcUrl(networkConfig.getRpcUrl("avalanche"))
                .explorerUrl(networkConfig.getExplorerUrl("avalanche"))
                .chainId(networkConfig.getChainId("avalanche"))
                .nativeCurrency("AVAX")
                .build());

        networkInfo.put("arbitrum", NetworkInfo.builder()
                .name(networkConfig.getNetworkDisplayName("arbitrum"))
                .isTestnet(networkConfig.isTestnetForNetwork("arbitrum"))
                .rpcUrl(networkConfig.getRpcUrl("arbitrum"))
                .explorerUrl(networkConfig.getExplorerUrl("arbitrum"))
                .chainId(networkConfig.getChainId("arbitrum"))
                .nativeCurrency("ETH")
                .build());

        networkInfo.put("tron", NetworkInfo.builder()
                .name(networkConfig.getNetworkDisplayName("tron"))
                .isTestnet(networkConfig.isTestnetForNetwork("tron"))
                .rpcUrl(networkConfig.getRpcUrl("tron"))
                .explorerUrl(networkConfig.getExplorerUrl("tron"))
                .chainId(0)
                .nativeCurrency("TRX")
                .build());

        networkInfo.put("solana", NetworkInfo.builder()
                .name(networkConfig.getNetworkDisplayName("solana"))
                .isTestnet(networkConfig.isTestnetForNetwork("solana"))
                .rpcUrl(networkConfig.getRpcUrl("solana"))
                .explorerUrl(networkConfig.getExplorerUrl("solana"))
                .chainId(0)
                .nativeCurrency("SOL")
                .build());

        networkInfo.put("bitcoin", NetworkInfo.builder()
                .name(networkConfig.getNetworkDisplayName("bitcoin"))
                .isTestnet(networkConfig.isTestnetForNetwork("bitcoin"))
                .rpcUrl(networkConfig.getRpcUrl("bitcoin"))
                .explorerUrl(networkConfig.getExplorerUrl("bitcoin"))
                .chainId(0)
                .nativeCurrency("BTC")
                .build());

        return networkInfo;
    }

    /**
     * Test connection to all enabled networks
     */
    public Map<String, Boolean> testNetworkConnections() {
        Map<String, Boolean> connections = new HashMap<>();

        try {
            connections.put("ethereum", ethereumChainService.getNetworkConfig() != null);
        } catch (Exception e) {
            log.error("Ethereum connection test failed: ", e);
            connections.put("ethereum", false);
        }

        try {
            connections.put("bsc", bscChainService.getNetworkConfig() != null);
        } catch (Exception e) {
            log.error("BSC connection test failed: ", e);
            connections.put("bsc", false);
        }

        try {
            connections.put("polygon", polygonChainService.getNetworkConfig() != null);
        } catch (Exception e) {
            log.error("Polygon connection test failed: ", e);
            connections.put("polygon", false);
        }

        try {
            connections.put("avalanche", avalancheChainService.getNetworkConfig() != null);
        } catch (Exception e) {
            log.error("Avalanche connection test failed: ", e);
            connections.put("avalanche", false);
        }

        try {
            connections.put("arbitrum", arbitrumChainService.getNetworkConfig() != null);
        } catch (Exception e) {
            log.error("Arbitrum connection test failed: ", e);
            connections.put("arbitrum", false);
        }

        try {
            connections.put("tron", tronChainService.getNetworkConfig() != null);
        } catch (Exception e) {
            log.error("TRON connection test failed: ", e);
            connections.put("tron", false);
        }

        try {
            connections.put("solana", solanaChainService.getNetworkConfig() != null);
        } catch (Exception e) {
            log.error("Solana connection test failed: ", e);
            connections.put("solana", false);
        }

        try {
            connections.put("bitcoin", bitcoinChainService.getNetworkConfig() != null);
        } catch (Exception e) {
            log.error("Bitcoin connection test failed: ", e);
            connections.put("bitcoin", false);
        }

        return connections;
    }

    public static class NetworkInfo {
        private String name;
        private boolean isTestnet;
        private String rpcUrl;
        private String explorerUrl;
        private Integer chainId;
        private String nativeCurrency;

        public static NetworkInfoBuilder builder() {
            return new NetworkInfoBuilder();
        }

        // Getters
        public String getName() {
            return name;
        }

        public boolean isTestnet() {
            return isTestnet;
        }

        public String getRpcUrl() {
            return rpcUrl;
        }

        public String getExplorerUrl() {
            return explorerUrl;
        }

        public Integer getChainId() {
            return chainId;
        }

        public String getNativeCurrency() {
            return nativeCurrency;
        }

        public static class NetworkInfoBuilder {
            private String name;
            private boolean isTestnet;
            private String rpcUrl;
            private String explorerUrl;
            private Integer chainId;
            private String nativeCurrency;

            public NetworkInfoBuilder name(String name) {
                this.name = name;
                return this;
            }

            public NetworkInfoBuilder isTestnet(boolean isTestnet) {
                this.isTestnet = isTestnet;
                return this;
            }

            public NetworkInfoBuilder rpcUrl(String rpcUrl) {
                this.rpcUrl = rpcUrl;
                return this;
            }

            public NetworkInfoBuilder explorerUrl(String explorerUrl) {
                this.explorerUrl = explorerUrl;
                return this;
            }

            public NetworkInfoBuilder chainId(Integer chainId) {
                this.chainId = chainId;
                return this;
            }

            public NetworkInfoBuilder nativeCurrency(String nativeCurrency) {
                this.nativeCurrency = nativeCurrency;
                return this;
            }

            public NetworkInfo build() {
                NetworkInfo info = new NetworkInfo();
                info.name = this.name;
                info.isTestnet = this.isTestnet;
                info.rpcUrl = this.rpcUrl;
                info.explorerUrl = this.explorerUrl;
                info.chainId = this.chainId;
                info.nativeCurrency = this.nativeCurrency;
                return info;
            }
        }
    }
}