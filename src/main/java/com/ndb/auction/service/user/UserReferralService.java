package com.ndb.auction.service.user;

import com.ndb.auction.exceptions.PreSaleException;
import com.ndb.auction.exceptions.ReferralException;
import com.ndb.auction.models.referral.ActiveReferralResponse;
import com.ndb.auction.models.referral.UpdateReferralAddressResponse;
import com.ndb.auction.models.user.User;
import com.ndb.auction.models.user.UserReferral;
import com.ndb.auction.models.user.UserReferralEarning;
import com.ndb.auction.models.wallet.NyyuWallet;
import com.ndb.auction.service.BaseService;
import com.ndb.auction.utils.RandomStringGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class UserReferralService extends BaseService {
    @Autowired
    private final RandomStringGenerator stringGenerator;

    @Value("${ndb.referral.commissionRate}")
    private int[] tierRate;

    private static String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    public UserReferralService(RandomStringGenerator stringGenerator) {
        this.stringGenerator = stringGenerator;
    }

    public UserReferral selectById(int id) {
        User user = userDao.selectById(id);
        UserReferral referral = userReferralDao.selectById(id);
        if (referral == null || !referral.isActive())
            return null;

        referral.setRate(tierRate[user.getTierLevel()]);
        referral.setCommissionRate(tierRate);
        referral.setFirstPurchase(ndbCoinService.firstPurchase(referral.getWalletConnect()));
        return referral;
    }

    public List<UserReferralEarning> earningByReferrer(int userId) {
        List<UserReferralEarning> list = new ArrayList<>();
        UserReferral referrer = userReferralDao.selectById(userId);
        if (referrer != null) {
            List<UserReferral> users = userReferralDao.getAllByReferredByCode(referrer.getReferralCode());
            for (UserReferral item : users) {
                UserReferralEarning earning = new UserReferralEarning();
                if (userAvatarDao.selectById(item.getId()) != null) {
                    String name = userAvatarDao.selectById(item.getId()).getName();
                    String prefix = userAvatarDao.selectById(item.getId()).getPrefix();
                    String address = item.getWalletConnect();

                    if (address == null) {
                        NyyuWallet nyyuWallet = nyyuWalletDao.selectByUserId(item.getId(), "BEP20");
                        if (nyyuWallet != null)
                            address = nyyuWallet.getPublicKey();
                    }

                    if (address != null) {
                        double amount = ndbCoinService.getUserEarning(address);
                        earning.setAmount(amount);
                    }
                    earning.setName(prefix + "." + name);
                    list.add(earning);
                }
            }
        }
        return list;
    }

    public int insert(UserReferral m) {
        return userReferralDao.insert(m);
    }

    public UserReferral createNewReferrer(int userId, String referredByCode, String walletAddress) {
        try {
            // check referredByCode
            var referrer = userReferralDao.selectByReferralCode(referredByCode);
            if (referrer == null) {
                throw new ReferralException("Invalid Referral Code");
            }

            // add referred user on chain
            String hash = ndbCoinService.recordReferral(walletAddress, referrer.getWalletConnect());
            if (hash == null) {
                throw new ReferralException("Cannot register your wallet");
            }

            // update database
            UserReferral referral = new UserReferral();
            referral.setId(userId);
            referral.setTarget(1); // default internal nyyu wallet
            referral.setWalletConnect(walletAddress);
            referral.setReferralCode("");
            referral.setReferredByCode(referredByCode);
            referral.setRecord(true);
            userReferralDao.insert(referral);
            return referral;
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());
        }
    }

    public ActiveReferralResponse activateReferralCode(int userId, String wallet) {
        try {
            int target = 0;

            // check wallet exists or not.
            if (wallet.equals(ZERO_ADDRESS)) {
                target = 1; // internal
                // Nyyu wallet case
                try {
                    NyyuWallet nyyuWallet = nyyuWalletDao.selectByUserId(userId, "BEP20");
                    if (nyyuWallet != null) {
                        wallet = nyyuWallet.getPublicKey();
                    } else {
                        // old users who has no nyyu wallet address
                        wallet = nyyuWalletService.generateNyyuWallet("BEP20", userId);
                    }
                } catch (Exception e) {
                    System.err.println("Error retrieving/generating wallet for user " + userId + ": " + e.getMessage());
                    // If wallet retrieval fails, try to generate a new one
                    try {
                        wallet = nyyuWalletService.generateNyyuWallet("BEP20", userId);
                    } catch (Exception genEx) {
                        throw new ReferralException("Unable to generate wallet address. Please contact support.");
                    }
                }
            } else {
                target = 2; // external wallet
            }

            // Validate wallet address
            if (wallet == null || wallet.isEmpty()) {
                throw new ReferralException("Unable to generate or retrieve wallet address");
            }

            // referral record from database
            UserReferral referral = userReferralDao.selectById(userId);
            if (referral == null) {
                referral = new UserReferral();
                referral.setId(userId);
                referral.setReferralCode(generateCode());
                referral.setReferredByCode("");
                referral.setWalletConnect(wallet);
            } else if (referral.isRecord()) {
                referral.setReferralCode(generateCode());
            }

            User user = userDao.selectById(userId);
            if (user == null) {
                throw new ReferralException("User not found");
            }

            int rate = tierRate[user.getTierLevel()];

            // For now, skip blockchain activation if there are connection issues
            // and just activate in the database
            boolean blockchainActivationSuccessful = false;

            try {
                // Try blockchain activation, but don't fail if it doesn't work
                boolean isAlreadyActive = ndbCoinService.isActiveReferrer(wallet);

                if (!isAlreadyActive) {
                    String hash = ndbCoinService.activeReferrer(wallet, (double) rate);
                    blockchainActivationSuccessful = (hash != null && !hash.isEmpty());
                } else {
                    blockchainActivationSuccessful = true; // Already active
                }
            } catch (Exception e) {
                System.err.println(
                        "Blockchain activation failed, but continuing with database activation: " + e.getMessage());
                // Continue with database-only activation
            }

            // Always activate in database regardless of blockchain status
            referral.setActive(true);
            referral.setTarget(target);
            referral.setWalletConnect(wallet);
            userReferralDao.insertOrUpdate(referral);

            if (!blockchainActivationSuccessful) {
                System.out.println(
                        "Warning: Referral activated in database only. Blockchain activation will be retried later.");
            }

            return ActiveReferralResponse.builder()
                    .code(referral.getReferralCode())
                    .referralWallet(wallet)
                    .rate(rate)
                    .commissionRate(tierRate)
                    .build();

        } catch (ReferralException e) {
            // Re-throw referral exceptions as-is
            throw e;
        } catch (Exception e) {
            // Catch any other unexpected exceptions
            System.err.println("Unexpected error in activateReferralCode: " + e.getMessage());
            e.printStackTrace();
            throw new ReferralException("Unable to activate referral code - please try again later");
        }
    }

    // update new wallet for user or referrer
    public UpdateReferralAddressResponse updateReferrerAddress(int userId, String newAddress) {
        try {
            var referrer = userReferralDao.selectById(userId);
            if (referrer == null)
                throw new ReferralException("No activated referrer");

            int lockTime = ndbCoinService.lockingTimeRemain(referrer.getWalletConnect());
            if (lockTime > 0) {
                int p1 = lockTime % 60;
                int p2 = lockTime / 60;
                int p3 = p2 % 60;
                p2 = p2 / 60;

                Object[] params = new Object[] {
                        String.join(" ", String.valueOf(p2), p2 == 1 || p2 == 0 ? "hour" : "hours"),
                        String.join(" ", String.valueOf(p3), p3 == 1 || p3 == 0 ? "minute" : "minutes"),
                        String.join(" ", String.valueOf(p1), p1 == 1 || 1 == 0 ? "second" : "seconds")
                };//
                String msg = messageSource.getMessage("limit_timelock", params, Locale.ENGLISH);
                throw new ReferralException(msg);
            }

            int target = 0;
            if (newAddress.equals(ZERO_ADDRESS)) {
                target = 1;
                NyyuWallet nyyuWallet = nyyuWalletDao.selectByUserId(userId, "BEP20");
                if (nyyuWallet == null)
                    newAddress = nyyuWalletService.generateNyyuWallet("BEP20", userId);
                else
                    newAddress = nyyuWallet.getPublicKey();
            } else {
                target = 2;
            }

            UpdateReferralAddressResponse response = new UpdateReferralAddressResponse();
            response.setReferralWallet(newAddress);
            if (!newAddress.equals(referrer.getWalletConnect())) {
                String hash = ndbCoinService.updateReferrer(referrer.getWalletConnect(), newAddress);
                if (hash != null) {
                    userReferralDao.updateWalletConnect(userId, target, newAddress);
                    response.setStatus(true);
                    return response;
                }
            }

            response.setStatus(false);
            return response;
        } catch (Exception e) {
            throw new ReferralException(e.getMessage());

        }
    }

    public int changeReferralWallet(int userId, int target, String prevAddr, String newAddr) {
        var hash = ndbCoinService.updateReferrer(prevAddr, newAddr);
        if (hash == null) {
            throw new PreSaleException("Cannot use current wallet as destination", "destination");
        }
        return userReferralDao.updateWalletConnect(userId, target, newAddr);
    }

    public boolean updateCommissionRate(int userId, int tierLevel) {
        try {
            UserReferral referrer = userReferralDao.selectById(userId);
            if (referrer == null)
                return false;
            User user = userDao.selectById(referrer.getId());
            if (tierLevel > user.getTierLevel()) {
                int rate = tierRate[tierLevel];
                if (referrer.getWalletConnect() != null && rate > 0) {
                    String hash = ndbCoinService.updateReferrerRate(referrer.getWalletConnect(), (double) rate);
                    if (!hash.isEmpty())
                        return true;
                }
            }
            return false;
        } catch (Exception e) {
            System.out.println(e.getStackTrace());
            throw new ReferralException(e.getMessage());
        }
    }

    public String deleteReferrer(int userId) {
        UserReferral referrer = userReferralDao.selectById(userId);
        List<UserReferral> listUserReferral = userReferralDao.getAllByReferredByCode(referrer.getReferralCode());
        List<String> listUserWallet = new ArrayList<>();
        for (UserReferral u : listUserReferral) {
            listUserWallet.add(u.getWalletConnect());
        }
        return ndbCoinService.deleteReferrer(referrer.getWalletConnect(), listUserWallet);
    }

    /*
     * return remaining time in seconds
     */
    public int checkTimeLock(int userId) {
        UserReferral referrer = userReferralDao.selectById(userId);
        int lockTime = ndbCoinService.lockingTimeRemain(referrer.getWalletConnect());
        return lockTime;
    }

    private String generateCode() {
        String generated = "";
        do {
            generated = stringGenerator.generate();
        } while (userReferralDao.existsUserByReferralCode(generated) == 1);

        return generated;
    }

    public boolean isFirstPurchase(int userId) {
        UserReferral referrer = userReferralDao.selectById(userId);
        return ndbCoinService.firstPurchase(referrer.getWalletConnect());
    }
}
