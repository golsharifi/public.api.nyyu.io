package com.ndb.auction.config;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.http.HttpService;

import java.util.concurrent.TimeUnit;

@Configuration
@ConfigurationProperties(prefix = "web3j")
public class Web3jConfig {

    private static Log log = LogFactory.getLog(Web3jConfig.class);

    // Configuration properties
    private String clientAddress;
    private String adminWalletPassword;
    private String localNetwork;
    private String gasPrice;
    private String gasLimit;

    // Nested configuration classes
    private Contract contract = new Contract();
    private Wallet wallet = new Wallet();
    private Network network = new Network();

    // Your existing service building method
    public Web3jService buildService(String clientAddress) {
        Web3jService web3jService;

        // Always use HTTP service for remote connections
        if (clientAddress == null || clientAddress.equals("")) {
            web3jService = new HttpService(createOkHttpClient());
        } else if (clientAddress.startsWith("http")) {
            web3jService = new HttpService(clientAddress, createOkHttpClient(), false);
        } else if (clientAddress.startsWith("wss://") || clientAddress.startsWith("ws://")) {
            // For WebSocket connections (not currently used)
            web3jService = new HttpService(clientAddress, createOkHttpClient(), false);
        } else {
            // For local development, default to HTTP
            web3jService = new HttpService("http://localhost:8545", createOkHttpClient(), false);
        }

        return web3jService;
    }

    // Create main Web3j bean
    @Bean
    public Web3j web3j() {
        return Web3j.build(buildService(this.clientAddress));
    }

    // Create local network Web3j bean
    @Bean("localWeb3j")
    public Web3j localWeb3j() {
        return Web3j.build(buildService(this.localNetwork));
    }

    // Your existing HTTP client methods
    private OkHttpClient createOkHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        configureLogging(builder);
        configureTimeouts(builder);
        return builder.build();
    }

    private void configureTimeouts(OkHttpClient.Builder builder) {
        Long tos = 300L;
        if (tos != null) {
            builder.connectTimeout(tos, TimeUnit.SECONDS);
            builder.readTimeout(tos, TimeUnit.SECONDS);
            builder.writeTimeout(tos, TimeUnit.SECONDS);
        }
    }

    private static void configureLogging(OkHttpClient.Builder builder) {
        if (log.isDebugEnabled()) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor(log::debug);
            logging.setLevel(HttpLoggingInterceptor.Level.BODY);
            builder.addInterceptor(logging);
        }
    }

    // Getters and Setters for configuration properties
    public String getClientAddress() {
        return clientAddress;
    }

    public void setClientAddress(String clientAddress) {
        this.clientAddress = clientAddress;
    }

    public String getAdminWalletPassword() {
        return adminWalletPassword;
    }

    public void setAdminWalletPassword(String adminWalletPassword) {
        this.adminWalletPassword = adminWalletPassword;
    }

    public String getLocalNetwork() {
        return localNetwork;
    }

    public void setLocalNetwork(String localNetwork) {
        this.localNetwork = localNetwork;
    }

    public String getGasPrice() {
        return gasPrice;
    }

    public void setGasPrice(String gasPrice) {
        this.gasPrice = gasPrice;
    }

    public String getGasLimit() {
        return gasLimit;
    }

    public void setGasLimit(String gasLimit) {
        this.gasLimit = gasLimit;
    }

    public Contract getContract() {
        return contract;
    }

    public void setContract(Contract contract) {
        this.contract = contract;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public void setWallet(Wallet wallet) {
        this.wallet = wallet;
    }

    public Network getNetwork() {
        return network;
    }

    public void setNetwork(Network network) {
        this.network = network;
    }

    // Inner classes for nested properties
    public static class Contract {
        private String ndbWallet;
        private String localToken;

        public String getNdbWallet() {
            return ndbWallet;
        }

        public void setNdbWallet(String ndbWallet) {
            this.ndbWallet = ndbWallet;
        }

        public String getLocalToken() {
            return localToken;
        }

        public void setLocalToken(String localToken) {
            this.localToken = localToken;
        }
    }

    public static class Wallet {
        private String demoPrivateKey1;
        private String demoPrivateKey2;

        public String getDemoPrivateKey1() {
            return demoPrivateKey1;
        }

        public void setDemoPrivateKey1(String demoPrivateKey1) {
            this.demoPrivateKey1 = demoPrivateKey1;
        }

        public String getDemoPrivateKey2() {
            return demoPrivateKey2;
        }

        public void setDemoPrivateKey2(String demoPrivateKey2) {
            this.demoPrivateKey2 = demoPrivateKey2;
        }
    }

    public static class Network {
        private String bscMainnet;
        private String bscTestnet;
        private String ethereumMainnet;

        public String getBscMainnet() {
            return bscMainnet;
        }

        public void setBscMainnet(String bscMainnet) {
            this.bscMainnet = bscMainnet;
        }

        public String getBscTestnet() {
            return bscTestnet;
        }

        public void setBscTestnet(String bscTestnet) {
            this.bscTestnet = bscTestnet;
        }

        public String getEthereumMainnet() {
            return ethereumMainnet;
        }

        public void setEthereumMainnet(String ethereumMainnet) {
            this.ethereumMainnet = ethereumMainnet;
        }
    }
}