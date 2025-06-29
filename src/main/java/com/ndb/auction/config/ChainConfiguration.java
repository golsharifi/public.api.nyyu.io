package com.ndb.auction.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Configuration class for multi-chain support
 * Ensures all chain services are properly scanned and configured
 */
@Configuration
@ComponentScan(basePackages = {
        "com.ndb.auction.web3.chains"
})
public class ChainConfiguration {

    // This class ensures all chain services are properly loaded
    // You can add additional configuration here if needed
}