package com.ndb.auction.service.withdraw;

import java.util.List;

import com.ndb.auction.dao.oracle.withdraw.CryptoWithdrawDao;
import com.ndb.auction.models.withdraw.BaseWithdraw;
import com.ndb.auction.models.withdraw.CryptoWithdraw;
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
public class CryptoWithdrawService extends BaseService {

    @Autowired
    private CryptoWithdrawDao cryptoWithdrawDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private VerificationEnforcementService verificationEnforcementService;

    public BaseWithdraw createNewWithdrawRequest(BaseWithdraw baseWithdraw) {
        CryptoWithdraw withdrawRequest = (CryptoWithdraw) baseWithdraw;

        try {
            // Get user information for verification check
            User user = userDao.selectById(withdrawRequest.getUserId());
            if (user == null) {
                throw new RuntimeException("User not found");
            }

            // Enforce verification requirements for withdrawals
            // Use USD amount if available, otherwise use amount
            double checkAmount = withdrawRequest.getWithdrawAmount();

            verificationEnforcementService.enforceVerificationRequirements(
                    user, "withdraw", checkAmount);

            // Proceed with original withdrawal logic
            BaseWithdraw result = cryptoWithdrawDao.insert(withdrawRequest);

            log.info("Crypto withdrawal request created successfully for user {} USD amount {} token amount {}",
                    withdrawRequest.getUserId(), withdrawRequest.getWithdrawAmount(), withdrawRequest.getTokenAmount());

            return result;

        } catch (VerificationRequiredException e) {
            log.error("Verification required for crypto withdrawal: user={}, amount={}, error={}",
                    withdrawRequest.getUserId(), withdrawRequest.getWithdrawAmount(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error creating crypto withdrawal for user {}: {}",
                    withdrawRequest.getUserId(), e.getMessage());
            throw new RuntimeException("Failed to create withdrawal request: " + e.getMessage());
        }
    }

    public int confirmWithdrawRequest(int requestId, int status, String reason) throws Exception {
        return cryptoWithdrawDao.confirmWithdrawRequest(requestId, status, reason);
    }

    public int updateCryptoWithdrawTxHash(int withdrawId, String hash) {
        return cryptoWithdrawDao.updateCryptoWithdarwTxHash(withdrawId, hash);
    }

    public List<? extends BaseWithdraw> getWithdrawRequestByUser(int userId, int status) {
        return cryptoWithdrawDao.selectByUser(userId, status);
    }

    public List<? extends BaseWithdraw> getWithdrawRequestByStatus(int userId, int approvedStatus) {
        return cryptoWithdrawDao.selectByStatus(userId, approvedStatus);
    }

    public List<? extends BaseWithdraw> getAllPendingWithdrawRequests() {
        return cryptoWithdrawDao.selectPendings();
    }

    public List<? extends BaseWithdraw> getAllWithdrawRequests() {
        return cryptoWithdrawDao.selectAll();
    }

    public BaseWithdraw getWithdrawRequestById(int id, int showStatus) {
        return cryptoWithdrawDao.selectById(id, showStatus);
    }

    public int changeShowStatus(int id, int showStatus) {
        return cryptoWithdrawDao.changeShowStatus(id, showStatus);
    }

}
