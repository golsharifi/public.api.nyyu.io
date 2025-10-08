package com.ndb.auction.models.wallet;

import java.math.BigDecimal;

public class WalletBalance {
    private String tokenSymbol;
    private BigDecimal balance;

    public WalletBalance(String tokenSymbol, BigDecimal balance) {
        this.tokenSymbol = tokenSymbol;
        this.balance = balance;
    }

    public static WalletBalance of(String tokenSymbol, BigDecimal balance) {
        return new WalletBalance(tokenSymbol, balance);
    }

    public static WalletBalance zero(String tokenSymbol) {
        return new WalletBalance(tokenSymbol, BigDecimal.ZERO);
    }

    // Getters and setters
    public String getTokenSymbol() {
        return tokenSymbol;
    }

    public void setTokenSymbol(String tokenSymbol) {
        this.tokenSymbol = tokenSymbol;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}