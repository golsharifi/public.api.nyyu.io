package com.ndb.auction.resolver.payment.withdarw;

import com.ndb.auction.exceptions.BalanceException;
import com.ndb.auction.exceptions.UnauthorizedException;
import com.ndb.auction.exceptions.UserSuspendedException;
import com.ndb.auction.hooks.BaseController;
import com.ndb.auction.models.Notification;
import com.ndb.auction.models.withdraw.CryptoWithdraw;
import com.ndb.auction.models.withdraw.Token;
import com.ndb.auction.resolver.BaseResolver;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.ndb.auction.service.utils.MailService;
import com.ndb.auction.service.utils.SMSService;
import com.ndb.auction.service.utils.TotpService;
import com.ndb.auction.service.withdraw.CryptoWithdrawService;
import com.ndb.auction.service.withdraw.TokenService;
import com.ndb.auction.web3.WithdrawWalletService;
import freemarker.template.TemplateException;
import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.mail.MessagingException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class CryptoWithdrawResolver extends BaseResolver implements GraphQLQueryResolver, GraphQLMutationResolver {

    @Value("${super.phone}")
    private String superPhone;

    @Value("${withdraw.check.pub}")
    private String PUBLIC_KEY;

    @Value("${withdraw.check.priv}")
    private String PRIVATE_KEY;

    private final double BEP20FEE = 1;
    private final double ERC20FEE = 20;

    private final double MIN_WITHDRAW_BEP20 = 10;
    private final double MIN_WITHDRAW_ERC20 = 30;
    private final double MIN_WITHDRAW_NDB = 100;

    private final CryptoWithdrawService cryptoWithdrawService;

    private final WithdrawWalletService adminWalletService;

    private final TotpService totpService;

    private final MailService mailService;

    private final SMSService smsService;

    private final TokenService tokenService;

    @PreAuthorize("isAuthenticated()")
    public CryptoWithdraw cryptoWithdrawRequest(
            double amount,
            String sourceToken,
            String network,
            String des,
            String code) throws MessagingException {

        // Get the HTTP request from Spring's RequestContextHolder
        jakarta.servlet.http.HttpServletRequest request = ((org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder
                .getRequestAttributes())
                .getRequest();

        if (request == null) {
            throw new UnauthorizedException("Unable to access HTTP request", "request");
        }

        String token = request.getHeader("x-auth-token");
        String key = request.getHeader("x-auth-key");
        String ts = request.getHeader("x-auth-ts");
        String payload = ts + "." + sourceToken + "." + network + "." + des + "." + code;
        String hmac = BaseController.buildHmacSignature(payload, PRIVATE_KEY);
        if (!key.equals(PUBLIC_KEY) || !token.equals(hmac))
            throw new UnauthorizedException("something went wrong", "signature");

        var userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();
        var userEmail = userDetails.getEmail();
        var user = userService.getUserById(userId);
        if (user.getIsSuspended()) {
            String msg = messageSource.getMessage("user_suspended", null, Locale.ENGLISH);
            throw new UserSuspendedException(msg);
        }

        var kycStatus = shuftiService.kycStatusCkeck(userId);
        if (!kycStatus) {
            String msg = messageSource.getMessage("no_kyc", null, Locale.ENGLISH);
            throw new UnauthorizedException(msg, "userId");
        }

        // check withdraw code
        if (!totpService.checkWithdrawCode(userEmail, code)) {
            String msg = messageSource.getMessage("invalid_twostep", null, Locale.ENGLISH);
            throw new BalanceException(msg, "code");
        }

        // check source token balance
        double sourceBalance = internalBalanceService.getFreeBalance(userId, sourceToken);
        if (sourceBalance < amount) {
            String msg = messageSource.getMessage("insufficient", null, Locale.ENGLISH);
            throw new BalanceException(msg, "amount");
        }

        // get crypto price
        double cryptoPrice = thirdAPIUtils.getCryptoPriceBySymbol(sourceToken);

        // check minimum
        if (network.equals("BEP20")) {
            if (sourceToken.equals("NDB") && amount * cryptoPrice < MIN_WITHDRAW_NDB) {
                var min = MIN_WITHDRAW_NDB / cryptoPrice;
                throw new BalanceException(
                        String.format("The minimum withdrawal amount for NDB token is %f %s.", min, sourceToken),
                        "amount");
            } else if (amount * cryptoPrice < MIN_WITHDRAW_BEP20) {
                var min = MIN_WITHDRAW_BEP20 / cryptoPrice;
                throw new BalanceException(
                        String.format("The minimum withdrawal amount for BSC Network is %f %s.", min, sourceToken),
                        "amount");
            }
        } else if (network.equals("ERC20")) {
            if (amount * cryptoPrice < MIN_WITHDRAW_ERC20) {
                var min = MIN_WITHDRAW_ERC20 / cryptoPrice;
                throw new BalanceException(
                        String.format("The minimum withdrawal amount for ETH Network is %f %s.", min, sourceToken),
                        "amount");
            }
        }

        // double totalUSD = amount * cryptoPrice;
        double fee = getTierFee(userId, amount);

        // network fee
        if (cryptoPrice > 0.0) {
            if (network.equals("ERC20")) {
                fee += ERC20FEE / cryptoPrice;
            } else if (network.equals("BEP20")) {
                fee += BEP20FEE / cryptoPrice;
            } else {
                throw new BalanceException("Not supported withdrawal.", "amount");
            }
        }

        double withdrawAmount = amount - fee;

        var m = new CryptoWithdraw(userId, withdrawAmount, fee, sourceToken, cryptoPrice, amount, network, des);
        var res = (CryptoWithdraw) cryptoWithdrawService.createNewWithdrawRequest(m);

        var superUsers = userService.getUsersByRole("ROLE_SUPER");
        try {
            mailService.sendWithdrawRequestNotifyEmail(superUsers, user, String.format("Crypto(%s)", network),
                    sourceToken, withdrawAmount, sourceToken, des, null);
        } catch (TemplateException | IOException e) {
            e.printStackTrace();
        }
        return res;
    }

    // send confirm withdraw SMS code
    @PreAuthorize("hasRole('ROLE_SUPER')")
    public int sendWithdrawConfirmCode() throws IOException, TemplateException {
        // generate code
        String code = totpService.getWithdrawConfirmCode();
        smsService.sendSMS(superPhone, code);
        return 1;
    }

    // confirm paypal withdraw
    @PreAuthorize("hasRole('ROLE_SUPER')")
    @Transactional
    public int confirmCryptoWithdraw(int id, int status, String deniedReason, String code) throws Exception {

        if (!totpService.checkWithdrawConfirmCode(code)) {
            String msg = messageSource.getMessage("invalid_twostep", null, Locale.ENGLISH);
            throw new BalanceException(msg, "2FA");
        }

        var request = (CryptoWithdraw) cryptoWithdrawService.getWithdrawRequestById(id, 1);
        if (request.getStatus() != 0) {
            throw new BalanceException("Already processed.", "amount");
        }

        var result = cryptoWithdrawService.confirmWithdrawRequest(id, status, deniedReason);
        var tokenSymbol = request.getSourceToken();
        var tokenAmount = request.getTokenAmount();

        if (result == 1 && status == 1) {

            var balance = internalBalanceService.getBalance(request.getUserId(), tokenSymbol);
            if (balance.getFree() < tokenAmount) {
                String msg = messageSource.getMessage("insufficient", null, Locale.ENGLISH);
                throw new BalanceException(msg, "amount");
            }

            var transactionHash = "";
            if (tokenSymbol.equals("NDB")) {
                transactionHash = ndbCoinService.transferNDB(request.getUserId(), request.getDestination(),
                        request.getWithdrawAmount());
                if (transactionHash == null) {
                    // cannot transfer NDB
                    String msg = messageSource.getMessage("cannot_crypto_transfer", null, Locale.ENGLISH);
                    throw new UnauthorizedException(msg, "id");
                }
            } else {
                // transfer
                transactionHash = adminWalletService.withdrawToken(
                        request.getNetwork(),
                        request.getSourceToken(),
                        request.getDestination(),
                        request.getWithdrawAmount());
                if (transactionHash.equals("Failed")) {
                    String msg = messageSource.getMessage("cannot_crypto_transfer", null, Locale.ENGLISH);
                    throw new UnauthorizedException(msg, "id");
                }
            }

            cryptoWithdrawService.updateCryptoWithdrawTxHash(request.getId(), transactionHash);
            internalBalanceService.deductFree(request.getUserId(), tokenSymbol, tokenAmount);

            notificationService.sendNotification(
                    request.getUserId(),
                    Notification.PAYMENT_RESULT,
                    "PAYMENT CONFIRMED",
                    String.format("Your %f %s withdarwal request has been approved", tokenAmount, tokenSymbol));
            return result;
        } else if (status != 1) {
            notificationService.sendNotification(
                    request.getUserId(),
                    Notification.PAYMENT_RESULT,
                    "PAYMENT CONFIRMED",
                    String.format("Your %f %s withdarwal request has been denied", tokenAmount, tokenSymbol));
        }

        return result;
    }

    public String cancelTransaction(int nonce) {
        return adminWalletService.cancelTransaction(nonce);
    }

    @PreAuthorize("isAuthenticated()")
    @SuppressWarnings("unchecked")
    public List<CryptoWithdraw> getCryptoWithdrawByUser(int showStatus) {
        var userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();
        return (List<CryptoWithdraw>) cryptoWithdrawService.getWithdrawRequestByUser(userId, showStatus);
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    @SuppressWarnings("unchecked")
    public List<CryptoWithdraw> getCryptoWithdrawByUserByAdmin(int userId) {
        return (List<CryptoWithdraw>) cryptoWithdrawService.getWithdrawRequestByUser(userId, 1);
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    @SuppressWarnings("unchecked")
    public List<CryptoWithdraw> getAllCryptoWithdraws() {
        return (List<CryptoWithdraw>) cryptoWithdrawService.getAllWithdrawRequests();
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    @SuppressWarnings("unchecked")
    public List<CryptoWithdraw> getCryptoWithdrawByStatusByAdmin(int userId, int status) {
        return (List<CryptoWithdraw>) cryptoWithdrawService.getWithdrawRequestByStatus(userId, status);
    }

    @PreAuthorize("isAuthenticated()")
    @SuppressWarnings("unchecked")
    public List<CryptoWithdraw> getCryptoWithdrawByStatus(int status) {
        var userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();
        return (List<CryptoWithdraw>) cryptoWithdrawService.getWithdrawRequestByStatus(userId, status);
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    @SuppressWarnings("unchecked")
    public List<CryptoWithdraw> getCryptoPendingWithdrawRequests() {
        return (List<CryptoWithdraw>) cryptoWithdrawService.getAllPendingWithdrawRequests();
    }

    @PreAuthorize("isAuthenticated()")
    public CryptoWithdraw getCryptoWithdrawById(int id, int showStatus) {
        var userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();
        var m = (CryptoWithdraw) cryptoWithdrawService.getWithdrawRequestById(id, showStatus);
        if (m.getUserId() != userId)
            return null;
        return m;
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public CryptoWithdraw getCryptoWithdrawByIdByAdmin(int id) {
        return (CryptoWithdraw) cryptoWithdrawService.getWithdrawRequestById(id, 1);
    }
    ////////////////////////////////// ADMIN WALLET

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public double getAdminWalletBalance(String network, String token) {
        return adminWalletService.getBalance(network, token);
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public Token addNewWithdrawToken(String tokenName, String tokenSymbol, String network, String address) {
        return tokenService.addNewToken(tokenName, tokenSymbol, network, address, true);
    }

    @PreAuthorize("isAuthenticated()")
    public int changeCryptoWithdrawShowStatus(int id, int showStatus) {
        return cryptoWithdrawService.changeShowStatus(id, showStatus);
    }

}
