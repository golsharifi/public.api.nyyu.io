package com.ndb.auction.service.withdraw;

import java.util.List;
import java.util.Locale;

import com.ndb.auction.dao.oracle.balance.CryptoBalanceDao;
import com.ndb.auction.dao.oracle.withdraw.BankWithdrawDao;
import com.ndb.auction.exceptions.BalanceException;
import com.ndb.auction.models.Notification;
import com.ndb.auction.models.withdraw.BankWithdrawRequest;
import com.ndb.auction.service.BaseService;
import com.ndb.auction.dao.oracle.user.UserDao;
import com.ndb.auction.exceptions.VerificationRequiredException;
import com.ndb.auction.models.user.User;
import com.ndb.auction.service.VerificationEnforcementService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class BankWithdrawService extends BaseService {

    @Autowired
    private BankWithdrawDao bankWithdrawDao;

    @Autowired
    private CryptoBalanceDao balanceDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private VerificationEnforcementService verificationEnforcementService;

    // create new withdraw
    public int createNewRequest(BankWithdrawRequest withdrawRequest) {
        try {
            // Get user information for verification check
            User user = userDao.selectById(withdrawRequest.getUserId());
            if (user == null) {
                throw new RuntimeException("User not found");
            }

            // Enforce verification requirements for bank withdrawals
            // Enforce verification requirements for bank withdrawals
            verificationEnforcementService.enforceVerificationRequirements(
                    user, "withdraw", withdrawRequest.getWithdrawAmount());

            // Proceed with original withdrawal logic
            int result = bankWithdrawDao.insert(withdrawRequest);

            log.info("Bank withdrawal request created successfully for user {} amount {}",
                    withdrawRequest.getUserId(), withdrawRequest.getWithdrawAmount());

            return result;

        } catch (VerificationRequiredException e) {
            log.error("Verification required for bank withdrawal: user={}, amount={}, error={}",
                    withdrawRequest.getUserId(), withdrawRequest.getWithdrawAmount(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error creating bank withdrawal for user {}: {}",
                    withdrawRequest.getUserId(), e.getMessage());
            throw new RuntimeException("Failed to create withdrawal request: " + e.getMessage());
        }
    }

    public List<BankWithdrawRequest> getAllPendingRequests() {
        return bankWithdrawDao.selectPending();
    }

    public List<BankWithdrawRequest> getAllApproved() {
        return bankWithdrawDao.selectApproved();
    }

    public List<BankWithdrawRequest> getAllDenied() {
        return bankWithdrawDao.selectDenied();
    }

    public List<BankWithdrawRequest> getAllRequests() {
        return bankWithdrawDao.selectAll();
    }

    public List<BankWithdrawRequest> getRequestsByUser(int userId, int status) {
        return bankWithdrawDao.selectByUser(userId, status);
    }

    public BankWithdrawRequest getRequestById(int id, int status) {
        return bankWithdrawDao.selectById(id, status);
    }

    public int approveRequest(int id) {
        int result = bankWithdrawDao.approveRequest(id);
        if (result == 1) {
            // success
            // ignore show status
            var request = bankWithdrawDao.selectById(id, 1);
            var tokenId = tokenAssetService.getTokenIdBySymbol(request.getSourceToken());

            // get free balance
            var balance = balanceDao.selectById(request.getUserId(), tokenId);
            if (balance.getFree() < request.getTokenAmount()) {
                String msg = messageSource.getMessage("insufficient", null, Locale.ENGLISH);
                throw new BalanceException(msg, "amount");
            }

            balanceDao.deductFreeBalance(request.getUserId(), tokenId, request.getTokenAmount());

            notificationService.sendNotification(
                    request.getUserId(),
                    Notification.PAYMENT_RESULT,
                    "PAYMENT CONFIRMED",
                    "Your withdarwal request has been approved");
            return result;
        } else {
            return result;
        }
    }

    public int denyRequest(int id, String reason) {
        int result = bankWithdrawDao.denyRequest(id, reason);
        if (result == 1) {
            // success
            var request = bankWithdrawDao.selectById(id, 1);
            notificationService.sendNotification(
                    request.getUserId(),
                    Notification.PAYMENT_RESULT,
                    "PAYMENT CONFIRMED",
                    String.format("Your withdarwal request has been denied. %s", reason));
            return result;
        } else {
            return result;
        }
    }

    public int changeShowStatus(int id, int showStatus) {
        return bankWithdrawDao.changeShowStatus(id, showStatus);
    }

}
