package com.ndb.auction.resolver.payment.withdarw;

import com.ndb.auction.exceptions.BalanceException;
import com.ndb.auction.exceptions.UnauthorizedException;
import com.ndb.auction.exceptions.UserSuspendedException;
import com.ndb.auction.models.withdraw.BankWithdrawRequest;
import com.ndb.auction.payload.BankMeta;
import com.ndb.auction.resolver.BaseResolver;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.ndb.auction.service.utils.MailService;
import com.ndb.auction.service.utils.TotpService;
import com.ndb.auction.service.withdraw.BankWithdrawService;
import freemarker.template.TemplateException;
import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

@Component
public class BankWithdrawResolver extends BaseResolver implements GraphQLMutationResolver, GraphQLQueryResolver {

    @Autowired
    private BankWithdrawService bankWithdrawService;

    @Autowired
    private TotpService totpService;

    @Autowired
    private MailService mailService;

    @PreAuthorize("isAuthenticated()")
    public BankWithdrawRequest bankWithdrawRequest(
            String targetCurrency,
            double amount, // requested amount in source token!
            String sourceToken,
            int mode,
            String country, // ignore for international
            String holderName,
            String bankName,
            String accNumber,
            String metadata,
            String address,
            String postCode,
            String code) throws MessagingException {
        // check user and kyc status
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

        // get token price
        double tokenPrice = thirdAPIUtils.getCryptoPriceBySymbol(sourceToken);

        // get token balance
        double tokenBalance = internalBalanceService.getFreeBalance(userId, sourceToken);
        double usdBalance = amount * tokenPrice;

        // get target currency price
        double fiatAmount = 0.0;
        if (targetCurrency.equals("USD")) {
            fiatAmount = usdBalance;
        } else {
            double fiatPrice = thirdAPIUtils.getCurrencyRate(targetCurrency);
            fiatAmount = usdBalance * fiatPrice;
        }

        // checking balance
        if (tokenBalance < amount) {
            String msg = messageSource.getMessage("insufficient", null, Locale.ENGLISH);
            throw new BalanceException(msg, "amount");
        }

        // get fee in usd
        var fee = getTierFee(userId, fiatAmount);
        var withdrawAmount = fiatAmount - fee; // amount in target currency

        var m = new BankWithdrawRequest(
                userId, targetCurrency, withdrawAmount, fee, sourceToken, tokenPrice, amount,
                mode, country, holderName, bankName, accNumber, metadata, address, postCode);
        bankWithdrawService.createNewRequest(m);

        // send request email
        var superUsers = userService.getUsersByRole("ROLE_SUPER");
        try {
            var bankMeta = new BankMeta(
                    bankName, address, "swift code", accNumber);
            mailService.sendWithdrawRequestNotifyEmail(superUsers, user, "Bank", sourceToken, withdrawAmount,
                    targetCurrency, "", bankMeta);
        } catch (TemplateException | IOException e) {
            e.printStackTrace();
        }
        return m;
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public List<BankWithdrawRequest> getPendingBankWithdrawRequests() {
        return bankWithdrawService.getAllPendingRequests();
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public List<BankWithdrawRequest> getAllApprovedBankWithdrawRequests() {
        return bankWithdrawService.getAllApproved();
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public List<BankWithdrawRequest> getAllDeniedBankWithdrawRequests() {
        return bankWithdrawService.getAllDenied();
    }

    @PreAuthorize("isAuthenticated()")
    public List<BankWithdrawRequest> getBankWithdrawRequestsByUser(int showStatus) {
        var userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();
        return bankWithdrawService.getRequestsByUser(userId, showStatus);
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public List<BankWithdrawRequest> getBankWithdrawRequestsByAdmin() {
        return bankWithdrawService.getAllRequests();
    }

    @PreAuthorize("isAuthenticated()")
    public BankWithdrawRequest getBankWithdrawRequestById(int id, int showStatus) {
        var userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        int userId = userDetails.getId();
        var m = bankWithdrawService.getRequestById(id, showStatus);
        if (m.getUserId() != userId)
            return null;
        return m;
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public BankWithdrawRequest getBankWithdrawRequestByIdByAdmin(int id) {
        return bankWithdrawService.getRequestById(id, 1);
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public int approveBankWithdrawRequest(int id, String code) {

        if (!totpService.checkWithdrawConfirmCode(code)) {
            String msg = messageSource.getMessage("invalid_twostep", null, Locale.ENGLISH);
            throw new BalanceException(msg, "2FA");
        }

        return bankWithdrawService.approveRequest(id);
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public int denyBankWithdrawRequest(int id, String reason) {
        return bankWithdrawService.denyRequest(id, reason);
    }

    @PreAuthorize("isAuthenticated()")
    public int changeBankWithdrawShowStatus(int id, int showStatus) {
        return bankWithdrawService.changeShowStatus(id, showStatus);
    }

}
