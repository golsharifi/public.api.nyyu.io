package com.ndb.auction.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import com.ndb.auction.service.TestnetService;
import com.ndb.auction.config.NetworkConfiguration;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/testnet")
@Slf4j
public class TestnetController {

    @Autowired
    private TestnetService testnetService;

    @Autowired
    private NetworkConfiguration networkConfig;

    /**
     * Get current testnet status for all networks
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> getTestnetStatus() {
        try {
            Map<String, Boolean> status = testnetService.getTestnetStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to get testnet status: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get network information including testnet details
     */
    @GetMapping("/networks")
    public ResponseEntity<Map<String, TestnetService.NetworkInfo>> getNetworkInfo() {
        try {
            Map<String, TestnetService.NetworkInfo> networkInfo = testnetService.getNetworkInfo();
            return ResponseEntity.ok(networkInfo);
        } catch (Exception e) {
            log.error("Failed to get network info: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get testnet faucet URLs
     */
    @GetMapping("/faucets")
    public ResponseEntity<Map<String, String>> getTestnetFaucets() {
        try {
            Map<String, String> faucets = testnetService.getTestnetFaucets();
            return ResponseEntity.ok(faucets);
        } catch (Exception e) {
            log.error("Failed to get faucet info: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Test connections to all networks
     */
    @GetMapping("/test-connections")
    public ResponseEntity<Map<String, Boolean>> testNetworkConnections() {
        try {
            Map<String, Boolean> connections = testnetService.testNetworkConnections();
            return ResponseEntity.ok(connections);
        } catch (Exception e) {
            log.error("Failed to test network connections: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get global testnet setting
     */
    @GetMapping("/global-setting")
    public ResponseEntity<Map<String, Object>> getGlobalTestnetSetting() {
        try {
            Map<String, Object> setting = Map.of(
                    "testnetEnabled", networkConfig.isTestnetEnabled(),
                    "message", networkConfig.isTestnetEnabled() ? "Global testnet mode is enabled"
                            : "Global testnet mode is disabled");
            return ResponseEntity.ok(setting);
        } catch (Exception e) {
            log.error("Failed to get global testnet setting: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Health check endpoint for admin panel
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        try {
            Map<String, Boolean> networkStatus = testnetService.testNetworkConnections();
            long healthyNetworks = networkStatus.values().stream()
                    .mapToLong(status -> status ? 1 : 0)
                    .sum();

            Map<String, Object> health = Map.of(
                    "status", healthyNetworks > 0 ? "UP" : "DOWN",
                    "totalNetworks", networkStatus.size(),
                    "healthyNetworks", healthyNetworks,
                    "testnetMode", networkConfig.isTestnetEnabled(),
                    "networks", networkStatus);

            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("Health check failed: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}