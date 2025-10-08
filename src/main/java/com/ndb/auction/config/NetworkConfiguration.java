package com.ndb.auction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

import java.util.Map;
import java.util.HashMap;

@Configuration
@ConfigurationProperties(prefix = "blockchain")
@Data
public class NetworkConfiguration {

    // Global testnet flag
    private boolean testnetEnabled = false;

    // Network specific configurations
    private Map<String, NetworkSettings> networks = new HashMap<>();

    // Individual network testnet overrides
    private Map<String, Boolean> testnetOverrides = new HashMap<>();

    @Data
    public static class NetworkSettings {
        private String mainnetRpc;
        private String testnetRpc;
        private String mainnetExplorer;
        private String testnetExplorer;
        private Integer mainnetChainId;
        private Integer testnetChainId;
        private String nativeCurrency;
        private boolean enabled = true;
    }

    /**
     * Check if a specific network should use testnet
     */
    public boolean isTestnetForNetwork(String networkName) {
        // Check network-specific override first
        Boolean override = testnetOverrides.get(networkName.toLowerCase());
        if (override != null) {
            return override;
        }
        // Fall back to global setting
        return testnetEnabled;
    }

    /**
     * Get RPC URL for a network (mainnet or testnet)
     */
    public String getRpcUrl(String networkName) {
        NetworkSettings settings = networks.get(networkName.toLowerCase());
        if (settings == null) {
            throw new IllegalArgumentException("Network not configured: " + networkName);
        }

        return isTestnetForNetwork(networkName) ? settings.getTestnetRpc() : settings.getMainnetRpc();
    }

    /**
     * Get explorer URL for a network
     */
    public String getExplorerUrl(String networkName) {
        NetworkSettings settings = networks.get(networkName.toLowerCase());
        if (settings == null) {
            throw new IllegalArgumentException("Network not configured: " + networkName);
        }

        return isTestnetForNetwork(networkName) ? settings.getTestnetExplorer() : settings.getMainnetExplorer();
    }

    /**
     * Get chain ID for a network
     */
    public Integer getChainId(String networkName) {
        NetworkSettings settings = networks.get(networkName.toLowerCase());
        if (settings == null) {
            throw new IllegalArgumentException("Network not configured: " + networkName);
        }

        return isTestnetForNetwork(networkName) ? settings.getTestnetChainId() : settings.getMainnetChainId();
    }

    /**
     * Get network display name with testnet suffix if applicable
     */
    public String getNetworkDisplayName(String networkName) {
        String baseName = networkName.substring(0, 1).toUpperCase() + networkName.substring(1).toLowerCase();
        return isTestnetForNetwork(networkName) ? baseName + " Testnet" : baseName;
    }
}