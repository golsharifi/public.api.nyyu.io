package com.ndb.auction.resolver.payment.deposit;

import com.ndb.auction.exceptions.UserNotFoundException;
import com.ndb.auction.exceptions.UserSuspendedException;
import com.ndb.auction.models.Notification;
import com.ndb.auction.models.TaskSetting;
import com.ndb.auction.models.tier.Tier;
import com.ndb.auction.models.tier.TierTask;
import com.ndb.auction.models.tier.WalletTask;
import com.ndb.auction.models.transactions.paypal.PaypalTransaction;
import com.ndb.auction.models.user.User;
import com.ndb.auction.payload.BalancePayload;
import com.ndb.auction.payload.request.paypal.OrderDTO;
import com.ndb.auction.payload.request.paypal.PayPalAppContextDTO;
import com.ndb.auction.payload.request.paypal.PurchaseUnit;
import com.ndb.auction.payload.response.paypal.CaptureOrderResponseDTO;
import com.ndb.auction.payload.response.paypal.OrderResponseDTO;
import com.ndb.auction.payload.response.paypal.PaymentLandingPage;
import com.ndb.auction.resolver.BaseResolver;
import com.ndb.auction.service.TaskSettingService;
import com.ndb.auction.service.TierService;
import com.ndb.auction.service.payment.paypal.PaypalDepositService;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.ndb.auction.service.utils.MailService;
import com.ndb.auction.utils.PaypalHttpClient;
import com.ndb.auction.utils.ThirdAPIUtils;

import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;
import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class DepositPaypal extends BaseResolver implements GraphQLMutationResolver, GraphQLQueryResolver {

    private final PaypalDepositService paypalDepositService;
    private final PaypalHttpClient payPalHttpClient;
    private final ThirdAPIUtils apiUtil;
    private final TierService tierService;
    private final TaskSettingService taskSettingService;
    private final MailService mailService;

    @PreAuthorize("isAuthenticated()")
    public OrderResponseDTO paypalForDeposit(Double amount, String currencyCode, String cryptoType) throws Exception {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        if (userService.isUserSuspended(userId)) {
            String msg = messageSource.getMessage("user_suspended", null, Locale.ENGLISH);
            throw new UserSuspendedException(msg);
        }

        // amount to usd value
        double usdAmount = 0.0;
        if (currencyCode.equals("USD")) {
            usdAmount = amount;
        } else {
            usdAmount = apiUtil.currencyConvert(currencyCode, "USD", amount);
        }

        double fee = getPaypalFee(userId, usdAmount);
        double cryptoPrice = thirdAPIUtils.getCryptoPriceBySymbol(cryptoType);

        double deposited = (usdAmount - fee) / cryptoPrice;

        var order = new OrderDTO();
        var unit = new PurchaseUnit(amount.toString(), currencyCode);
        order.getPurchaseUnits().add(unit);

        var appContext = new PayPalAppContextDTO();
        appContext.setReturnUrl(WEBSITE_URL + "/");
        appContext.setBrandName("Deposit");
        appContext.setLandingPage(PaymentLandingPage.BILLING);
        order.setApplicationContext(appContext);
        OrderResponseDTO orderResponse = payPalHttpClient.createOrder(order);

        var m = PaypalTransaction.builder()
                .userId(userId)
                .txnType("DEPOSIT")
                .txnId(0)
                .fiatType(currencyCode)
                .fiatAmount(amount)
                .usdAmount(usdAmount)
                .fee(fee)
                .paypalOrderId(orderResponse.getId())
                .paypalOrderStatus(orderResponse.getStatus().name())
                .cryptoType(cryptoType)
                .cryptoAmount(deposited)
                .status(0)
                .shown(false)
                .build();

        // Verification enforcement happens here automatically
        paypalDepositService.insert(m);
        return orderResponse;
    }

    @PreAuthorize("isAuthenticated()")
    public boolean captureOrderForDeposit(String orderId) throws Exception {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();

        var m = paypalDepositService.selectByPaypalOrderId(orderId);
        if (m == null || m.getUserId() != userId) {
            String msg = messageSource.getMessage("no_transaction", null, Locale.ENGLISH);
            throw new UserNotFoundException(msg, "orderId");
        }

        CaptureOrderResponseDTO responseDTO = payPalHttpClient.captureOrder(orderId);
        if (responseDTO.getStatus() != null && responseDTO.getStatus().equals("COMPLETED")) {
            // check deposited amount & update status
            paypalDepositService.updateOrderStatus(m.getId(), "COMPLETED");

            // add balance to user
            internalBalanceService.addFreeBalance(userId, "USDT", m.getCryptoAmount(), "Paypal deposit");

            List<BalancePayload> balances = internalBalanceService.getInternalBalances(userId);
            double totalBalance = 0.0;
            for (BalancePayload balance : balances) {
                // get price and total balance
                double _price = apiUtil.getCryptoPriceBySymbol(balance.getTokenSymbol());
                double _balance = _price * (balance.getFree() + balance.getHold());
                totalBalance += _balance;
            }

            User user = userService.getUserById(userId);
            List<Tier> tierList = tierService.getUserTiers();
            TaskSetting taskSetting = taskSettingService.getTaskSetting();
            TierTask tierTask = tierTaskService.getTierTask(userId);

            if (tierTask == null) {
                tierTask = new TierTask(userId);
                tierTaskService.updateTierTask(tierTask);
            }

            if (tierTask.getWallet() < totalBalance) {

                // get point
                double gainedPoint = 0.0;
                for (WalletTask task : taskSetting.getWallet()) {
                    if (tierTask.getWallet() > task.getAmount())
                        continue;
                    if (totalBalance > task.getAmount()) {
                        // add point
                        gainedPoint += task.getPoint();
                    } else {
                        break;
                    }
                }

                double newPoint = user.getTierPoint() + gainedPoint;
                int tierLevel = 0;
                // check change in level
                for (Tier tier : tierList) {
                    if (tier.getPoint() <= newPoint) {
                        tierLevel = tier.getLevel();
                    }
                }
                userService.updateTier(user.getId(), tierLevel, newPoint);
                tierTask.setWallet(totalBalance);
                tierTaskService.updateTierTask(tierTask);
            }

            // send notification to user for payment result!!
            var msg = "";
            if (m.getCryptoType().equals("USDT") || m.getCryptoType().equals("USDC")) {
                var df = new DecimalFormat("#.00");
                msg = "Your deposit of " + df.format(m.getCryptoAmount()) + m.getCryptoType() + " was successful.";
            } else {
                var df = new DecimalFormat("#.00000000");
                msg = "Your deposit of " + df.format(m.getCryptoAmount()) + m.getCryptoType() + " was successful.";
            }

            var admins = userService.getUsersByRole("ROLE_SUPER");

            try {
                mailService.sendDeposit(user.getEmail(),
                        user.getAvatar().getPrefix() + " " + user.getAvatar().getName(),
                        "PayPal", m.getFiatType(), "USDT", m.getUsdAmount(), m.getCryptoAmount(), m.getFee(), admins);
            } catch (Exception e) {

            }

            notificationService.sendNotification(
                    userId,
                    Notification.PAYMENT_RESULT,
                    "PAYMENT CONFIRMED",
                    msg);
            return true;
        }
        return false;
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public List<PaypalTransaction> getAllPaypalDepositTxns(
            int status, int showStatus, Integer offset, Integer limit, String orderBy) {
        return paypalDepositService.selectAll(status, showStatus, offset, limit, "DEPOSIT", orderBy);
    }

    @PreAuthorize("isAuthenticated()")
    public List<PaypalTransaction> getPaypalDepositTxnsByUser(String orderBy, int showStatus) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        return paypalDepositService.selectByUser(userId, showStatus, orderBy);
    }

    @PreAuthorize("isAuthenticated()")
    public PaypalTransaction getPaypalDepositTxnById(int id) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        var tx = paypalDepositService.selectById(id);
        if (tx.getUserId() == userId)
            return tx;
        return null;
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public List<PaypalTransaction> getPaypalDepositTxnsByAdmin(String orderBy) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        return paypalDepositService.selectByUser(userId, 1, orderBy);
    }

    @PreAuthorize("hasRole('ROLE_SUPER')")
    public PaypalTransaction getPaypalDepositTxnByIdByAdmin(int id) {
        return paypalDepositService.selectById(id);
    }

    @PreAuthorize("isAuthenticated()")
    public int changePayPalDepositShowStatus(int id, int showStatus) {
        return paypalDepositService.changeShowStatus(id, showStatus);
    }

}
