package com.ndb.auction.service;

import java.util.ArrayList;
import java.util.List;

import com.ndb.auction.dao.oracle.balance.BalanceChangeDao;
import com.ndb.auction.models.TokenAsset;
import com.ndb.auction.models.balance.BalanceChange;
import com.ndb.auction.models.balance.CryptoBalance;
import com.ndb.auction.payload.BalancePerUser;

import lombok.RequiredArgsConstructor;

import com.ndb.auction.payload.BalancePayload;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InternalBalanceService extends BaseService {

    private final BalanceChangeDao balanceChangeDao;

    // getting balances
    public List<BalancePayload> getInternalBalances(int userId) {
        List<CryptoBalance> iBalances = balanceDao.selectByUserId(userId, null);
        List<BalancePayload> balanceList = new ArrayList<>();
        for (CryptoBalance balance : iBalances) {
            TokenAsset asset = tokenAssetService.getTokenAssetById(balance.getTokenId());
            BalancePayload b = new BalancePayload(asset.getTokenName(), asset.getTokenSymbol(), asset.getSymbol(), balance.getFree(), balance.getHold());
            balanceList.add(b);
        }
        return balanceList;
    }

    public List<BalancePerUser> getInternalBalancesAllUsers() {
        return balanceDao.selectAll();
    }

    public CryptoBalance getBalance(int userId, String symbol) {
        int tokenId = tokenAssetService.getTokenIdBySymbol(symbol);
        return balanceDao.selectById(userId, tokenId);
    }

    public double getFreeBalance(int userId, String symbol) {
        int tokenId = tokenAssetService.getTokenIdBySymbol(symbol);
        CryptoBalance balance = balanceDao.selectById(userId, tokenId);
        if (balance == null) return 0.0;
        return balance.getFree();
    }

    public int addFreeBalance(int userId, String cryptoType, Double amount, String reason) {
        int tokenId = tokenAssetService.getTokenIdBySymbol(cryptoType);

        // balance change log
        var balanceLog = BalanceChange.builder()
            .userId(userId)
            .tokenId(tokenId)
            .reason(reason)
            .amount(amount)
            .build();
        balanceChangeDao.insert(balanceLog);
        return balanceDao.addFreeBalance(userId, tokenId, amount);
    }

    public int addHoldBalance(int userId, String cryptoType, Double amount) {
        int tokenId = tokenAssetService.getTokenIdBySymbol(cryptoType);
        return balanceDao.addHoldBalance(userId, tokenId, amount);
    }

    public int makeHoldBalance(int userId, String cryptoType, double amount) {
        int tokenId = tokenAssetService.getTokenIdBySymbol(cryptoType);
        return balanceDao.makeHoldBalance(userId, tokenId, amount);
    }

    public int deductFree(int userId, String cryptoType, Double amount) {
        int tokenId = tokenAssetService.getTokenIdBySymbol(cryptoType);
        return balanceDao.deductFreeBalance(userId, tokenId, amount);
    }

}
