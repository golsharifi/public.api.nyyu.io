package com.ndb.auction.service.payment.paypal;

import java.util.List;

import com.ndb.auction.dao.oracle.transactions.paypal.PaypalTransactionDao;
import com.ndb.auction.dao.oracle.user.UserDao;
import com.ndb.auction.exceptions.VerificationRequiredException;
import com.ndb.auction.models.transactions.paypal.PaypalTransaction;
import com.ndb.auction.models.user.User;
import com.ndb.auction.service.VerificationEnforcementService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class PaypalDepositService extends PaypalBaseService {

    @Autowired
    private PaypalTransactionDao paypalTransactionDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private VerificationEnforcementService verificationEnforcementService;

    public PaypalTransaction insert(PaypalTransaction m) {
        try {
            // Get user information for verification check
            User user = userDao.selectById(m.getUserId());
            if (user == null) {
                throw new RuntimeException("User not found");
            }

            // Enforce verification requirements for PayPal deposits
            double checkAmount = m.getUsdAmount() > 0 ? m.getUsdAmount() : m.getFiatAmount();
            verificationEnforcementService.enforceVerificationRequirements(
                    user, "deposit", checkAmount);

            // Proceed with original deposit logic
            PaypalTransaction result = paypalTransactionDao.insert(m);

            log.info("PayPal deposit created successfully for user {} amount {} USD {}",
                    m.getUserId(), m.getFiatAmount(), m.getUsdAmount());

            return result;

        } catch (VerificationRequiredException e) {
            log.error("Verification required for PayPal deposit: user={}, amount={}, error={}",
                    m.getUserId(), m.getFiatAmount(), e.getMessage());
            throw e;
        }
    }

    // Other existing methods remain the same...
    public List<PaypalTransaction> selectAll(int status, int showStatus, Integer offset, Integer limit, String txnType,
            String orderBy) {
        return paypalTransactionDao.selectPage(status, showStatus, offset, limit, txnType, orderBy);
    }

    public List<PaypalTransaction> selectByUser(int userId, int showStatus, String orderBy) {
        return paypalTransactionDao.selectByUser(userId, showStatus, orderBy);
    }

    public PaypalTransaction selectById(int id) {
        return paypalTransactionDao.selectById(id);
    }

    public int updateOrderStatus(int id, String status) {
        return paypalTransactionDao.updateOrderStatus(id, status);
    }

    public int changeShowStatus(int id, int showStatus) {
        return paypalTransactionDao.changeShowStatus(id, showStatus);
    }
}