package com.ndb.auction.service.payment.bank;

import java.util.List;
import java.util.UUID;

import com.ndb.auction.dao.oracle.transactions.bank.BankDepositDao;
import com.ndb.auction.dao.oracle.user.UserDao;
import com.ndb.auction.exceptions.VerificationRequiredException;
import com.ndb.auction.models.transactions.Transaction;
import com.ndb.auction.models.transactions.bank.BankDepositTransaction;
import com.ndb.auction.models.user.User;
import com.ndb.auction.service.VerificationEnforcementService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class BankDepositService {

    @Autowired
    private BankDepositDao bankDepositDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private VerificationEnforcementService verificationEnforcementService;

    /**
     * Insert new bank deposit with verification enforcement
     */
    public BankDepositTransaction insert(BankDepositTransaction m) {
        try {
            // Get user information for verification check
            User user = userDao.selectById(m.getUserId());
            if (user == null) {
                throw new RuntimeException("User not found");
            }

            // Enforce verification requirements for deposits
            // Use USD amount if available, otherwise use amount
            double checkAmount = m.getUsdAmount() > 0 ? m.getUsdAmount() : m.getAmount();
            verificationEnforcementService.enforceVerificationRequirements(
                    user, "deposit", checkAmount);

            // Generate unique ID if not set
            if (m.getUid() == null || m.getUid().isEmpty()) {
                String uid = UUID.randomUUID().toString();
                m.setUid(uid);
            }

            // Proceed with original deposit logic
            BankDepositTransaction result = (BankDepositTransaction) bankDepositDao.insert(m);

            log.info("Bank deposit created successfully for user {} amount {} USD {}",
                    m.getUserId(), m.getAmount(), m.getUsdAmount());

            return result;

        } catch (VerificationRequiredException e) {
            log.error("Verification required for bank deposit: user={}, amount={}, error={}",
                    m.getUserId(), m.getAmount(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Error creating bank deposit for user {}: {}", m.getUserId(), e.getMessage());
            throw new RuntimeException("Failed to create bank deposit: " + e.getMessage());
        }
    }

    public List<? extends Transaction> selectAll(String orderBy) {
        return bankDepositDao.selectAll(orderBy);
    }

    public List<? extends Transaction> selectByUser(int userId, String orderBy, int status) {
        return bankDepositDao.selectByUser(userId, orderBy, status);
    }

    public Transaction selectById(int id, int status) {
        return bankDepositDao.selectById(id, status);
    }

    public int update(int id, int status) {
        return bankDepositDao.update(id, status);
    }

    public int update(int id, String currency, double amount, double usdAmount, double deposited, double fee,
            String cryptoType, double cryptoPrice) {
        return bankDepositDao.update(id, currency, amount, usdAmount, deposited, fee, cryptoType, cryptoPrice);
    }

    public BankDepositTransaction selectByUid(String uid) {
        return bankDepositDao.selectByUid(uid);
    }

    public List<BankDepositTransaction> selectUnconfirmedByAdmin() {
        return bankDepositDao.selectUnconfirmedByAdmin();
    }

    public List<BankDepositTransaction> selectUnconfirmedByUser(int userId) {
        return bankDepositDao.selectUnconfirmedByUser(userId);
    }

    public int changeShowStatus(int id, int isShow) {
        return bankDepositDao.changeShowStatus(id, isShow);
    }

    /**
     * Check if user can make deposits based on verification status
     */
    public boolean canUserDeposit(int userId, double amount) {
        try {
            User user = userDao.selectById(userId);
            if (user == null)
                return false;

            return !verificationEnforcementService.isVerificationRequired(user, "deposit", amount) ||
                    verificationEnforcementService.isUserVerified(userId);
        } catch (Exception e) {
            log.error("Error checking deposit eligibility for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * Get verification requirements message for deposit
     */
    public String getVerificationMessage(int userId, double amount) {
        try {
            User user = userDao.selectById(userId);
            if (user == null)
                return "User not found";

            if (verificationEnforcementService.isCountryBlocked(user.getCountry())) {
                return "Bank deposits are not available in your country";
            }

            if (verificationEnforcementService.isVerificationRequired(user, "deposit", amount)) {
                return String.format("Identity verification required for deposits above $%.2f", amount);
            }

            return "No verification required";
        } catch (Exception e) {
            return "Error checking verification requirements";
        }
    }
}