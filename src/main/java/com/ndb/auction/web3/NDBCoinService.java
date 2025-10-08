package com.ndb.auction.web3;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ExecutionException;

import jakarta.annotation.PostConstruct;

import com.google.gson.Gson;
import com.ndb.auction.config.Web3jConfig;
import com.ndb.auction.contracts.NDBReferral;
import com.ndb.auction.contracts.NDBcoinV4;
import com.ndb.auction.exceptions.ReferralException;
import com.ndb.auction.models.SlackMessage;
import com.ndb.auction.models.digifinex.DigiFinex;
import com.ndb.auction.models.p2pb2b.P2PB2BResponse;
import com.ndb.auction.schedule.ScheduledTasks;
import com.ndb.auction.service.BaseService;

import com.ndb.auction.service.TokenAssetService;
import com.ndb.auction.utils.SlackUtils;
import lombok.extern.slf4j.Slf4j;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.contracts.eip20.generated.ERC20;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tuples.generated.Tuple2;
import org.web3j.tx.FastRawTransactionManager;

@Service
@Slf4j
public class NDBCoinService extends BaseService {

    @Value("${ndb.private.key}")
    private String ndbKey;

    @Value("${bsc.json.rpc}")
    private String bscNetwork;

    @Value("${bsc.json.chainid}")
    private long bscChainId;

    @Value("${ndb.token.addr}")
    private String ndbTokenContract;
    @Value("${ndb.referral.addr}")
    private String ndbReferralContract;

    @Value("${pancakev2.rpc}")
    private String pancakev2RPC;

    @Value("${ndb.referral.privKey}")
    private String[] referralPrivKey;

    @Autowired
    private SlackUtils slackUtils;

    @Autowired
    private ScheduledTasks schedule;

    @Autowired
    public TokenAssetService tokenAssetService;

    private Credentials ndbCredential;
    private NDBcoinV4 ndbToken;
    private NDBReferral ndbReferral;
    private FastRawTransactionManager txMananger;

    private Web3j BEP20NET = Web3j.build(new HttpService(bscNetwork));

    private final BigInteger gasPrice = new BigInteger("10000000000");
    private final BigInteger gasLimit = new BigInteger("800000");
    private final BigInteger decimals = new BigInteger("1000000000000");
    private final BigInteger m_decimals = new BigInteger("100000000");
    private final double multipler = 10000.0;

    Queue<String> referralKeyQueue = new LinkedList<>();

    @SuppressWarnings("deprecation")
    @PostConstruct
    public void init() {
        try {
            Web3jConfig web3jConfig = new Web3jConfig();
            BEP20NET = Web3j.build(web3jConfig.buildService(bscNetwork));
            // Web3j web3j = Web3j.build(new HttpService(bscNetwork));
            ndbCredential = Credentials.create(ndbKey);
            txMananger = new FastRawTransactionManager(BEP20NET, ndbCredential, bscChainId);
            ndbToken = NDBcoinV4.load(ndbTokenContract, BEP20NET, txMananger, gasPrice, gasLimit);

            for (String item : referralPrivKey) {
                referralKeyQueue.add(item);
            }
            setKeyBeforeExcuteTransaction();

            ndbToken.transferEventFlowable(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST)
                    .subscribe(event -> {
                        handleEvent(event);
                    }, error -> {
                        System.out.println("Error: " + error);
                    });
        } catch (Exception ex) {
            System.out.println("INIT WEB3 : " + ex.getMessage());
        }
    }

    private void handleActiveReferrer(NDBReferral.ReferrerActiveEventResponse event) {
        try {
            String txnHash = event.log.getTransactionHash();
            String transferMessage = "*ActiveReferrer " + (bscChainId == 56 ? "(mainnet)*" : "(testnet)*")
                    + "\n*Referrer:* " + event.referrer
                    + "\n*Rate:* " + event.rate
                    + "\n*Bscscan:* " + (bscChainId == 56 ? "https://bscscan.com/tx/" + txnHash
                            : "https://testnet.bscscan.com/tx/" + txnHash);

            SlackMessage slackMessage = SlackMessage.builder()
                    .channel((bscChainId == 56 ? "C03K6BANS7P" : "C03RS06B324"))
                    .username("ndbBot")
                    .text(transferMessage)
                    .icon_emoji(":bell:")
                    .build();
            slackUtils.sendMessage(slackMessage, SlackUtils.SlackChannel.REFERRAL);
        } catch (Exception e) {
            System.out.println(e.getStackTrace());
        }
    }

    private void handleRecordReferralEvent(NDBReferral.ReferralRecordedEventResponse event) {
        try {
            System.out.println(event.referrer);
            String txnHash = event.log.getTransactionHash();
            String transferMessage = "*RecordReferral " + (bscChainId == 56 ? "(mainnet)*" : "(testnet)*")
                    + "\n*Referrer:* " + event.referrer
                    + "\n*User:* " + event.user
                    + "\n*Bscscan:* " + (bscChainId == 56 ? "https://bscscan.com/tx/" + txnHash
                            : "https://testnet.bscscan.com/tx/" + txnHash);

            SlackMessage slackMessage = SlackMessage.builder()
                    .channel((bscChainId == 56 ? "C03K6BANS7P" : "C03RS06B324"))
                    .username("ndbBot")
                    .text(transferMessage)
                    .icon_emoji(":bell:")
                    .build();
            slackUtils.sendMessage(slackMessage, SlackUtils.SlackChannel.REFERRAL);
        } catch (Exception e) {
            System.out.println(e.getStackTrace());
        }
    }

    @SuppressWarnings("deprecation")
    private void setKeyBeforeExcuteTransaction() {
        var dynamicKey = referralKeyQueue.poll();
        Credentials credentialReferral = Credentials.create(dynamicKey);
        referralKeyQueue.add(dynamicKey);
        FastRawTransactionManager txManangerReferral = new FastRawTransactionManager(BEP20NET, credentialReferral,
                bscChainId);
        ndbReferral = NDBReferral.load(ndbReferralContract, BEP20NET, txManangerReferral, gasPrice, gasLimit);
        ndbReferral.referrerActiveEventFlowable(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST)
                .subscribe(event -> {
                    handleActiveReferrer(event);
                }, error -> {
                    System.out.println("referrerActiveEventFlowable error : " + error);
                });

        ndbReferral.referralRecordedEventFlowable(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST)
                .subscribe(event -> {
                    handleRecordReferralEvent(event);
                }, error -> {
                    System.out.println("referrerActiveEventFlowable error : " + error);
                });
    }

    public BigInteger getBalanceOf(String wallet) {
        try {
            BigInteger balance = ndbToken.balanceOf(wallet).send();
            return balance.divide(decimals);
        } catch (Exception e) {
            System.out.println("getBalance : " + e.getMessage());
            return BigInteger.ZERO;
        }
    }

    private void handleEvent(NDBcoinV4.TransferEventResponse event) {
        try {
            // create new withdraw transaction record
            BigInteger blockNumber = event.log.getBlockNumber();
            String txnHash = event.log.getTransactionHash();

            // add to unconfirmed list
            schedule.addPendingTxn(txnHash, blockNumber);
            System.out.println("Pending: " + txnHash);
            // Send notify to NDB slack
            String transferMessage = "*NDBtoken transfer " + (bscChainId == 56 ? "(mainnet)*" : "(testnet)*")
                    + "\n*From "
                    + (event.from.toLowerCase(Locale.ROOT).equals("0x2aba4f5683b765a6be05e037162164b8d02532b7")
                            ? " Presale wallet"
                            : "")
                    + (event.from.toLowerCase(Locale.ROOT).equals("0xffefe959d8baea028b1697abfc4285028d6ceb10")
                            ? " DigiFinex wallet"
                            : "")
                    + (event.from.toLowerCase(Locale.ROOT).equals("0x5be909e0d204a94cc93fc9d7940584b5ec59e618")
                            ? " P2pB2b wallet"
                            : "")
                    + ":* " + event.from
                    + "\n*To:* " + event.to
                    + "\n*Amount:* " + event.value.doubleValue() / Math.pow(10, 12) + " NDB"
                    + "\n*Bscscan:* " + (bscChainId == 56 ? "https://bscscan.com/tx/" + txnHash
                            : "https://testnet.bscscan.com/tx/" + txnHash);

            SlackMessage slackMessage = SlackMessage.builder()
                    .channel((bscChainId == 56 ? "C03K6BANS7P" : "C03RS06B324"))
                    .username("ndbBot")
                    .text(transferMessage)
                    .icon_emoji(":bell:")
                    .build();
            slackUtils.sendMessage(slackMessage, SlackUtils.SlackChannel.TOKEN);
        } catch (Exception e) {
            System.out.println(e.getStackTrace());
        }
    }

    public String activeReferrer(String address, Double rate) {
        try {
            setKeyBeforeExcuteTransaction();
            BigInteger _rate = BigInteger.valueOf(rate.longValue());
            TransactionReceipt receipt = ndbReferral.activeReferrer(address, _rate).send();
            return receipt.getTransactionHash();
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    public double getUserEarning(String address) {
        try {
            double earning = ndbReferral.getEarning(address).send().doubleValue() / Math.pow(10, 12);
            return earning;
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    public String recordReferral(String user, String referrer) {
        try {
            setKeyBeforeExcuteTransaction();
            TransactionReceipt receipt = ndbReferral.recordReferral(user, referrer).send();
            return receipt.getTransactionHash();
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    public String deleteReferrer(String referrer, List<String> users) {
        try {
            setKeyBeforeExcuteTransaction();
            TransactionReceipt receipt = ndbReferral.deleteReferrer(referrer, users).send();
            return receipt.getTransactionHash();
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    public int lockingTimeRemain(String userAddress) {
        try {
            int lockingTime = ndbReferral.lockingTimeRemain(userAddress).send().intValue();
            return lockingTime;
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    public boolean firstPurchase(String userAddress) {
        try {
            return ndbReferral.firstPurchase(userAddress).send().booleanValue();
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    public String updateReferrerRate(String referrer, Double rate) {
        try {
            setKeyBeforeExcuteTransaction();
            BigInteger _rate = BigInteger.valueOf(rate.longValue());
            TransactionReceipt receipt = ndbReferral.updateReferrerRate(referrer, _rate).send();
            return receipt.getTransactionHash();
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    public String updateReferrer(String old, String current) {
        try {
            setKeyBeforeExcuteTransaction();
            TransactionReceipt receipt = ndbReferral.updateReferrer(old, current).send();
            System.out.println(referralKeyQueue);
            return receipt.getTransactionHash();
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    public boolean isActiveReferrer(String referrer) throws ExecutionException, InterruptedException {
        Tuple2<BigInteger, BigInteger> result = ndbReferral.referrerDetails(referrer).sendAsync().get();
        if (result.getValue1().intValue() > 0)
            return true;
        else
            return false;
    }

    public boolean isReferralRecorded(String userWallet, String referrerWallet) {
        try {
            String result = ndbReferral.referrersByUser(userWallet).sendAsync().get();
            if (result.equals(referrerWallet.toLowerCase()))
                return true;
            else
                return false;
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    public double getTotalSupply() throws ExecutionException, InterruptedException {
        BigInteger total = ndbToken.totalSupply().sendAsync().get();
        return total.divide(decimals).doubleValue();
    }

    public double getMarketCap() throws Exception {
        Request p2pRequest = new Request.Builder()
                .url("https://api.p2pb2b.com/api/v2/public/ticker?market=NDB_USDT")
                .build();
        Response p2pResponse = new OkHttpClient().newCall(p2pRequest).execute();
        P2PB2BResponse p2pData = new Gson().fromJson(p2pResponse.body().string(), P2PB2BResponse.class);

        Request digiFinexRequest = new Request.Builder()
                .url("https://openapi.digifinex.com/v3/ticker?symbol=NDB_USDT")
                .build();
        Response digiFinexResponse = new OkHttpClient().newCall(digiFinexRequest).execute();
        DigiFinex digiFinexData = new Gson().fromJson(digiFinexResponse.body().string(), DigiFinex.class);

        double ciculatingSupply = this.getCirculatingSupply();
        double price = (Double.parseDouble(p2pData.result.last) + digiFinexData.ticker.get(0).last) / 2;
        double marketcap = ciculatingSupply * price;
        return marketcap;
    }

    public double getCirculatingSupply() throws Exception {
        return getTotalSupply() - ndbToken.balanceOf("0x5a99a143ba18184df9fddf6c60fae98eef1e9833").send()
                .divide(decimals).doubleValue();
        // return ndbToken.getCirculatingSupply().send().divide(decimals).doubleValue();
    }

    public Map<String, Object> getAll() {
        List<Double> priceList = new ArrayList<>();
        List<Double> changeList = new ArrayList<>();
        List<Double> volumeList = new ArrayList<>();
        try {
            Request p2pRequest = new Request.Builder()
                    .url("https://api.p2pb2b.com/api/v2/public/ticker?market=NDB_USDT")
                    .build();
            Response response = new OkHttpClient().newCall(p2pRequest).execute();
            if (response.code() != 200)
                throw new RuntimeException("RspCode=" + response.code());
            P2PB2BResponse p2pData = new Gson().fromJson(response.body().string(), P2PB2BResponse.class);
            priceList.add(Double.parseDouble(p2pData.result.last));
            changeList.add(Double.parseDouble(p2pData.result.change));
            volumeList.add(Double.parseDouble(p2pData.result.volume));
        } catch (Exception ex) {
            log.info("failed to get response from p2pb2b: {}", ex.getMessage());
        }
        try {
            Request digiFinexRequest = new Request.Builder()
                    .url("https://openapi.digifinex.com/v3/ticker?symbol=NDB_USDT")
                    .build();
            Response response = new OkHttpClient().newCall(digiFinexRequest).execute();
            if (response.code() != 200)
                throw new RuntimeException("RspCode=" + response.code());
            DigiFinex digiFinexData = new Gson().fromJson(response.body().string(), DigiFinex.class);
            priceList.add(digiFinexData.ticker.get(0).last);
            changeList.add(digiFinexData.ticker.get(0).change);
            volumeList.add(digiFinexData.ticker.get(0).vol);
        } catch (Exception ex) {
            log.info("failed to get response from digifinex: {}", ex.getMessage());
        }
        if (priceList.size() > 0) {
            double price = priceList.stream()
                    .mapToDouble(d -> d)
                    .average()
                    .orElse(0.0);
            double change = changeList.stream()
                    .mapToDouble(d -> d)
                    .average()
                    .orElse(0.0);
            double volume = volumeList.stream()
                    .mapToDouble(d -> d)
                    .sum();
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("price", price);
            resultMap.put("change", change);
            resultMap.put("volume", volume);
            try {
                double ciculatingSupply = this.getCirculatingSupply();
                double marketCap = ciculatingSupply * price;
                resultMap.put("ciculatingSupply", ciculatingSupply);
                resultMap.put("marketCap", marketCap);
            } catch (Exception ex) {
                log.info("failed to get circulating supply: {}", ex.getMessage());
            }
            return resultMap;
        }
        return null;
    }

    public NDBCoinService() {
    }

    public boolean checkConfirmation(BigInteger targetNumber) {
        // check confirm or not
        try {
            // Web3j web3j = Web3j.build(new HttpService(bscNetwork));
            BigInteger latestNumber = BEP20NET.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send()
                    .getBlock().getNumber();
            if (latestNumber.subtract(targetNumber).longValue() > 12L) {
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
            // no such block
            return false;
        }
        return false;
    }

    public String transferNDB(int userId, String address, Double amount) {
        try {

            Web3j web3j = Web3j.build(new HttpService(bscNetwork));
            ndbCredential = Credentials.create(ndbKey);
            @SuppressWarnings("deprecation")
            ERC20 ndbToken = ERC20.load(ndbTokenContract, web3j, ndbCredential, gasPrice, gasLimit);

            // avoid decimals
            amount *= multipler;
            BigInteger _amount = BigInteger.valueOf(amount.longValue());
            _amount = _amount.multiply(m_decimals);

            // create
            TransactionReceipt receipt = ndbToken.transfer(address, _amount).send();

            log.info("receipt hash: {}", receipt.getTransactionHash());
            log.info("receipt status: {}", receipt.getStatus());

            return receipt.getTransactionHash();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
