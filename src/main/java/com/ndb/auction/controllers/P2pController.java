package com.ndb.auction.controllers;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.ndb.auction.hooks.BaseController;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/p2p")
@Slf4j
public class P2pController extends BaseController {

    static long lastPriceTime;
    static ResponseEntity lastPriceResponse;

    @GetMapping(value = "/ndbcoin/price")
    public Object getNdbPrice() throws IOException {
        long currentTime = System.currentTimeMillis();
        if (lastPriceResponse == null || currentTime - lastPriceTime > 60000) {
            Request request = new Request.Builder()
                    .url("https://api.p2pb2b.com/api/v2/public/ticker?market=NDB_USDT")
                    .build();
            Response response = new OkHttpClient().newCall(request).execute();

            HttpHeaders headers = new HttpHeaders();
            headers.add("Content-Type", response.header("content-type"));

            lastPriceResponse = ResponseEntity
                    .status(response.code())
                    .headers(headers)
                    .body(response.body().string());
            lastPriceTime = currentTime;
        }
        return lastPriceResponse;
    }

    @GetMapping(value = "/ndbcoin/kline/digifinex")
    public Object getNdbKlineDigifinex() throws IOException {
        long startTime = System.currentTimeMillis() - 25 * 3600 * 1000;
        Request request = new Request.Builder()
                .url("https://openapi.digifinex.com/v3/kline?symbol=NDB_USDT&period=30&start_time=" + startTime)
                .build();
        Response response = new OkHttpClient().newCall(request).execute();
        if (response.code() != 200) {
            log.error("DigiFinex API error: {}", response.code());
            throw new RuntimeException("RspCode=" + response.code());
        }
        String responseString = response.body().string();
        JsonArray dataArray = JsonParser.parseString(responseString).getAsJsonObject().get("data").getAsJsonArray();
        int dataLength = dataArray.size();
        List<Double> resultList = new ArrayList<>();
        for (int i = dataLength - 48; i < dataLength; i++) {
            resultList.add(dataArray.get(i).getAsJsonArray().get(2).getAsDouble());
        }
        return resultList;
    }

    @GetMapping(value = "/ndbcoin/kline/p2p")
    public Object getNdbKlineP2p() throws IOException {
        Request request = new Request.Builder()
                .url("https://api.p2pb2b.com/api/v2/public/market/kline?market=NDB_USDT&interval=1h&offset=0&limit=50")
                .build();
        Response response = new OkHttpClient().newCall(request).execute();
        if (response.code() != 200) {
            log.error("P2PB2B API error: {}", response.code());
            throw new RuntimeException("RspCode=" + response.code());
        }
        String responseString = response.body().string();
        JsonArray dataArray = JsonParser.parseString(responseString).getAsJsonObject().get("result").getAsJsonArray();
        List<Double> resultList = new ArrayList<>();
        for (int i = 0; i < dataArray.size(); i++) {
            resultList.add(dataArray.get(i).getAsJsonArray().get(2).getAsDouble());
        }
        return resultList;
    }
}