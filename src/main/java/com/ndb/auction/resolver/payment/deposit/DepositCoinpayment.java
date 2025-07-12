package com.ndb.auction.resolver.payment.deposit;

import com.ndb.auction.exceptions.PaymentException;
import com.ndb.auction.exceptions.UnauthorizedException;
import com.ndb.auction.exceptions.UserSuspendedException;
import com.ndb.auction.exceptions.VerificationRequiredException;
import com.ndb.auction.models.transactions.coinpayment.CoinpaymentDepositTransaction;
import com.ndb.auction.models.wallet.NyyuWallet;
import com.ndb.auction.resolver.BaseResolver;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.ndb.auction.web3.NyyuWalletService;
import com.ndb.auction.models.user.User;
import com.ndb.auction.dao.oracle.user.UserDao;
import com.ndb.auction.service.VerificationEnforcementService;

import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;
import org.apache.http.client.ClientProtocolException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Component
public class DepositCoinpayment extends BaseResolver implements GraphQLMutationResolver, GraphQLQueryResolver {

    @Autowired
    UserDao userDao;

    @Autowired
    VerificationEnforcementService verificationEnforcementService;

    @Autowired
    NyyuWalletService nyyuWalletService;
    private static final String DEPOSIT = "DEPOSIT";

    // get deposit address
    @PreAuthorize("isAuthenticated()")
    public CoinpaymentDepositTransaction createChargeForDeposit(String coin, String network, String cryptoType)
            throws ClientProtocolException, IOException {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();

        if (userService.isUserSuspended(userId)) {
            String msg = messageSource.getMessage("user_suspended", null, Locale.ENGLISH);
            throw new UserSuspendedException(msg);
        }

        // Get user information for verification check
        User user = userDao.selectById(userId);
        if (user == null) {
            throw new UnauthorizedException("User not found", "userId");
        }

        // Use the centralized verification enforcement service
        try {
            verificationEnforcementService.enforceVerificationRequirements(user, "deposit", 0.0);
        } catch (VerificationRequiredException e) {
            throw new UnauthorizedException(e.getMessage(), "userId");
        }

        // Normalize network name
        network = network.equals("ERC20") ? "ETH" : network;

        try {
            // FIRST: Check if user already has an existing active deposit transaction for
            // this coin/network
            List<CoinpaymentDepositTransaction> existingTransactions = coinpaymentWalletService.selectByUser(userId, 0,
                    DEPOSIT);

            if (existingTransactions != null && !existingTransactions.isEmpty()) {
                for (CoinpaymentDepositTransaction existing : existingTransactions) {
                    // Check if there's an existing transaction for same coin/network combination
                    if (existing.getCoin().equals(coin) &&
                            existing.getNetwork().equals(network) &&
                            existing.getCryptoType().equals(cryptoType) &&
                            existing.getDepositStatus() == 0) { // 0 = pending

                        // Return existing transaction instead of creating new one
                        System.out.println("Returning existing deposit transaction ID: " + existing.getId());
                        return existing;
                    }
                }
            }

            // If no existing transaction found, create a new one
            CoinpaymentDepositTransaction m;

            // Handle different networks based on the original logic
            network = network.equals("ERC20") ? "BEP20" : network;

            if (network.equals("BEP20") || network.equals("TRC20") || network.equals("SOL")) {
                // Use NyyuWallet service for modern networks (BEP20, TRC20, SOL)
                m = new CoinpaymentDepositTransaction(0, userId, 0.0, 0.0, 0.0, DEPOSIT, cryptoType, network, coin);

                NyyuWallet nyyuWallet = nyyuWalletService.selectByUserId(userId, network);
                if (nyyuWallet != null) {
                    // check it is registered or not
                    if (nyyuWallet.getNyyuPayRegistered()) {
                        m.setDepositAddress(nyyuWallet.getPublicKey());
                    } else {
                        var address = nyyuWalletService.registerNyyuWallet(nyyuWallet);
                        if (address == null) {
                            String msg = messageSource.getMessage("no_registered_wallet", null, Locale.ENGLISH);
                            throw new PaymentException(msg, "cryptoType");
                        }
                        m.setDepositAddress(address);
                    }
                } else {
                    var address = nyyuWalletService.generateNyyuWallet(network, userId);
                    if (address == null) {
                        String msg = messageSource.getMessage("no_registered_wallet", null, Locale.ENGLISH);
                        throw new PaymentException(msg, "cryptoType");
                    }
                    m.setDepositAddress(address);
                }
                return m;
            } else {
                // Use traditional CoinPayments API for other networks (BTC, etc.)
                m = new CoinpaymentDepositTransaction(0, userId, 0.0, 0.0, 0.0, DEPOSIT, cryptoType, network, coin);

                try {
                    m = coinpaymentWalletService.createNewTransaction(m);
                    if (m != null) {
                        System.out.println("Created new deposit transaction ID: " + m.getId());
                        return m;
                    } else {
                        throw new PaymentException("Failed to create deposit transaction - external API error");
                    }
                } catch (Exception e) {
                    // If we still get a constraint violation, it might be a race condition
                    // Try to find the transaction that was just created by another request
                    System.err.println("Error creating transaction, checking for race condition: " + e.getMessage());

                    // Wait a moment and check again
                    try {
                        Thread.sleep(100);
                        List<CoinpaymentDepositTransaction> recentTransactions = coinpaymentWalletService
                                .selectByUser(userId, 0, DEPOSIT);
                        if (recentTransactions != null && !recentTransactions.isEmpty()) {
                            for (CoinpaymentDepositTransaction recent : recentTransactions) {
                                if (recent.getCoin().equals(coin) &&
                                        recent.getNetwork().equals(network) &&
                                        recent.getCryptoType().equals(cryptoType) &&
                                        recent.getDepositStatus() == 0) {

                                    System.out.println(
                                            "Found transaction created by concurrent request: " + recent.getId());
                                    return recent;
                                }
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    // If we still can't find a transaction, throw the original error
                    throw new PaymentException("Unable to create or retrieve deposit transaction: " + e.getMessage());
                }
            }

        } catch (PaymentException | UnauthorizedException | UserSuspendedException e) {
            // Re-throw these specific exceptions
            throw e;
        } catch (Exception e) {
            // Catch any other unexpected errors
            System.err.println("Unexpected error in createChargeForDeposit: " + e.getMessage());
            e.printStackTrace();
            throw new PaymentException("Unable to process deposit request - please try again");
        }
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public List<CoinpaymentDepositTransaction> getCoinpaymentDepositTx() {
        return coinpaymentWalletService.selectAll(DEPOSIT);
    }

    @PreAuthorize("isAuthenticated()")
    public List<CoinpaymentDepositTransaction> getCoinpaymentDepositTxByUser(int showStatus) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        return coinpaymentWalletService.selectByUser(userId, showStatus, DEPOSIT);
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public List<CoinpaymentDepositTransaction> getCoinpaymentDepositTxByAdmin(int userId) {
        return coinpaymentWalletService.selectByUser(userId, 1, DEPOSIT);
    }

    @PreAuthorize("isAuthenticated()")
    public CoinpaymentDepositTransaction getCoinpaymentDepositTxById(int id) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        var m = coinpaymentWalletService.selectById(id);

        // Add null check before accessing methods
        if (m == null) {
            throw new UnauthorizedException("Transaction not found.", "id");
        }

        if (m.getUserId() != userId) {
            throw new UnauthorizedException("You have no permission.", "id");
        }
        return m;
    }

    @PreAuthorize("isAuthenticated()")
    public int changeCoinpaymentDepositShowStatus(int id, int showStatus) {
        return coinpaymentWalletService.changeShowStatus(id, showStatus);
    }
}