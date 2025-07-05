package com.ndb.auction.utils;

import com.google.gson.Gson;
import com.ndb.auction.payload.CoinPrice;
import com.ndb.auction.payload.response.FiatConverted;
import com.ndb.auction.payload.response.FreaksResponse;
import com.ndb.auction.web3.NDBCoinService;

import org.apache.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class ThirdAPIUtils {
    private WebClient binanceAPI;
    private WebClient xchangeAPI;

    @Autowired
    private NDBCoinService ndbCoinService;

    private static Gson gson = new Gson();

    public ThirdAPIUtils(WebClient.Builder webClientBuilder) {
        this.binanceAPI = webClientBuilder
                .baseUrl("https://api.binance.com/api/v3")
                .build();
        this.xchangeAPI = webClientBuilder
                .baseUrl("https://api.exchangerate.host")
                .build();
    }

    public double getCryptoPriceBySymbol(String symbol) {
        String symbolPair = "";
        try {
            if (symbol.equals("USDC")) {
                return 1.0;
            } else if (symbol.equals("USDT")) {
                symbolPair = "USDCUSDT";
            } else if (symbol.equals("BUSD")) {
                symbolPair = "USDCBUSD";
            } else if (symbol.equals("NDB")) {
                var map = ndbCoinService.getAll();
                return (double) map.get("price");
            } else {
                symbolPair = symbol + "USDC";
            }

            String s = symbolPair;
            CoinPrice objs = binanceAPI.get()
                    .uri(uriBuilder -> uriBuilder.path("/ticker/price")
                            .queryParam("symbol", s.toUpperCase())
                            .build())
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .onStatus(
                            status -> status.is4xxClientError() || status.is5xxServerError(),
                            response -> {
                                // Log the error if needed
                                return Mono.error(
                                        new RuntimeException("API call failed with status: " + response.statusCode()));
                            })
                    .bodyToMono(CoinPrice.class)
                    .onErrorReturn(new CoinPrice()) // Return empty CoinPrice on error
                    .block();

            if (objs == null || objs.getPrice() == null) {
                return 0.0;
            }

            if (symbol.equals("USDT")) {
                return 1.0 / Double.valueOf(objs.getPrice());
            }
            return Double.valueOf(objs.getPrice());
        } catch (Exception e) {
            // Log the exception if needed
            System.err.println("Error getting crypto price for " + symbol + ": " + e.getMessage());
        }
        return 0.0;
    }

    public double currencyConvert(String from, String to, double amount) {
        try {
            String converted = xchangeAPI.get()
                    .uri(uriBuilder -> uriBuilder.path("/convert")
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("amount", amount)
                            .build())
                    .retrieve()
                    .onStatus(
                            status -> status.is4xxClientError() || status.is5xxServerError(),
                            response -> Mono.error(new RuntimeException("Currency conversion failed")))
                    .bodyToMono(String.class)
                    .block();

            FiatConverted fiatConverted = gson.fromJson(converted, FiatConverted.class);
            return fiatConverted.getResult();
        } catch (Exception e) {
            System.err.println("Error converting currency from " + from + " to " + to + ": " + e.getMessage());
        }
        return 0.0;
    }

    public double getCurrencyRate(String from) {
        if (from.equals("USD"))
            return 1.0;

        try {
            String converted = xchangeAPI.get()
                    .uri(uriBuilder -> uriBuilder.path("/latest")
                            .queryParam("symbols", from)
                            .queryParam("base", "USD")
                            .build())
                    .retrieve()
                    .onStatus(
                            status -> status.is4xxClientError() || status.is5xxServerError(),
                            response -> Mono.error(new RuntimeException("Currency rate fetch failed")))
                    .bodyToMono(String.class)
                    .block();

            var rates = gson.fromJson(converted, FreaksResponse.class);
            String rateString = rates.getRates().get(from);
            return Double.parseDouble(rateString);
        } catch (Exception e) {
            System.err.println("Error getting currency rate for " + from + ": " + e.getMessage());
        }
        return 0.0;
    }
}