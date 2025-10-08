package com.ndb.auction.service.user;

import org.springframework.stereotype.Service;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

@Service
public class FallbackWalletService {

    /**
     * Generate a simple wallet address without encryption as fallback
     */
    public String generateSimpleWallet() {
        try {
            ECKeyPair keyPair = Keys.createEcKeyPair();
            return "0x" + Keys.getAddress(keyPair);
        } catch (Exception e) {
            System.err.println("Failed to generate fallback wallet: " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if an address is a valid Ethereum address format
     */
    public boolean isValidAddress(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }

        // Check if it starts with 0x and has 42 characters total
        return address.matches("^0x[a-fA-F0-9]{40}$");
    }
}