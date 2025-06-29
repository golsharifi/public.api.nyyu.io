package com.ndb.auction.service;

import com.ndb.auction.models.nyyupay.NyyuPayRequest;
import com.ndb.auction.payload.response.NyyuWalletResponse;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class NyyuPayService extends BaseService {
    // NyyuPay URL
    @Value("${nyyupay.base}")
    private String NYYU_PAY_BASE;

    @Value("${nyyupay.pubKey}")
    private String PUBLIC_KEY;

    @Value("${nyyupay.privKey}")
    private String PRIVATE_KEY;

    private WebClient nyyuPayAPI;
    protected WebClient.Builder client;

    private static final Logger logger = LoggerFactory.getLogger(NyyuPayService.class);

    public NyyuPayService(WebClient.Builder webClientBuilder) {
        this.client = webClientBuilder;
    }

    @PostConstruct
    public void init() {
        this.nyyuPayAPI = this.client
                .baseUrl(NYYU_PAY_BASE)
                .build();
    }

    // Replace the existing sendNyyuPayRequest method with this:
    public boolean sendNyyuPayRequest(String network, String walletAddress) {
        if (network.equals("ERC20") || network.equals("BEP20")) {
            walletAddress = walletAddress.toLowerCase();
        }

        long ts = System.currentTimeMillis() / 1000L;
        var request = new NyyuPayRequest(walletAddress);
        String payload = String.valueOf(ts) + "POST" + "{\"address\":\"" + request.getAddress() + "\"}";
        String hmac = buildHmacSignature(payload, PRIVATE_KEY);

        try {
            logger.debug("Attempting NyyuPay request for network: {} address: {}", network, walletAddress);

            var response = nyyuPayAPI.post()
                    .uri(uriBuilder -> uriBuilder.path(network).build())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Auth-Token", hmac)
                    .header("X-Auth-Key", PUBLIC_KEY)
                    .header("X-Auth-Ts", String.valueOf(ts))
                    .body(Mono.just(request), NyyuPayRequest.class)
                    .retrieve()
                    .bodyToMono(NyyuWalletResponse.class)
                    .timeout(Duration.ofSeconds(10))
                    .onErrorReturn(createErrorResponse("Connection failed"))
                    .block();

            if (response != null && response.getError() == null) {
                logger.info("NyyuPay request successful for network: {} address: {}", network, walletAddress);
                return true;
            } else {
                logger.warn("NyyuPay request failed for network: {} address: {} error: {}",
                        network, walletAddress, response != null ? response.getError() : "null response");
                return false;
            }
        } catch (Exception e) {
            logger.error("Failed to connect to NyyuPay service for network: {} address: {} - {}",
                    network, walletAddress, e.getMessage());
            // Return false for now - wallet creation will continue without NyyuPay
            // registration
            return false;
        }
    }

    // Add this helper method
    private NyyuWalletResponse createErrorResponse(String error) {
        NyyuWalletResponse response = new NyyuWalletResponse();
        response.setError(error);
        response.setStatus("FAILED");
        return response;
    }
}