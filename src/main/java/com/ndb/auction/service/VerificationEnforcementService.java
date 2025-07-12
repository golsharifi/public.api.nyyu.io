package com.ndb.auction.service;

import com.ndb.auction.dao.oracle.user.UserVerifyDao;
import com.ndb.auction.dao.oracle.verify.KycSettingDao;
import com.ndb.auction.dao.oracle.verify.CountrySettingDao;
import com.ndb.auction.exceptions.VerificationRequiredException;
import com.ndb.auction.models.KYCSetting;
import com.ndb.auction.models.CountrySetting;
import com.ndb.auction.models.user.User;
import com.ndb.auction.models.user.UserVerify;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class VerificationEnforcementService extends BaseService {

    @Autowired
    private UserVerifyDao userVerifyDao;

    @Autowired
    private KycSettingDao kycSettingDao;

    @Autowired
    private CountrySettingDao countrySettingDao;

    /**
     * Check if verification is required for a specific operation
     */
    public boolean isVerificationRequired(User user, String operationType, double amount) {
        try {
            // Check if country is blocked
            if (isCountryBlocked(user.getCountry())) {
                throw new VerificationRequiredException(
                        String.format(
                                "Operations not allowed from your country (%s). Please contact support for assistance.",
                                user.getCountry()));
            }

            // Check if country is exempt from verification
            if (isCountryExemptFromVerification(user.getCountry())) {
                log.info("User from country {} is exempt from verification", user.getCountry());
                return false;
            }

            // Get KYC settings for operation type
            KYCSetting kycSetting = kycSettingDao.getKYCSetting("KYC");
            if (kycSetting == null) {
                log.warn("No KYC settings found, defaulting to require verification");
                return true;
            }

            double threshold = getThresholdForOperation(kycSetting, operationType);

            // If amount exceeds threshold, verification is required
            boolean required = amount >= threshold;

            log.debug("Verification check for user {} country {} operation {} amount {}: required={} (threshold={})",
                    user.getId(), user.getCountry(), operationType, amount, required, threshold);

            return required;

        } catch (VerificationRequiredException e) {
            throw e; // Re-throw verification exceptions
        } catch (Exception e) {
            log.error("Error checking verification requirements for user {}: {}", user.getId(), e.getMessage());
            // Default to requiring verification on error for security
            return true;
        }
    }

    /**
     * Enforce verification requirements - throws exception if not met
     */
    public void enforceVerificationRequirements(User user, String operationType, double amount) {
        if (!isVerificationRequired(user, operationType, amount)) {
            return; // No verification required
        }

        UserVerify userVerify = userVerifyDao.selectById(user.getId());
        if (userVerify == null) {
            throw new VerificationRequiredException(
                    String.format(
                            "Identity verification required for %s operations above $%.2f. Please complete KYC verification to continue.",
                            operationType, getKycThresholdForOperation(operationType)));
        }

        // Check KYC verification
        if (!userVerify.isKycVerified()) {
            double threshold = getKycThresholdForOperation(operationType);
            throw new VerificationRequiredException(
                    String.format(
                            "KYC verification required for %s of $%.2f (threshold: $%.2f). Please verify your identity to continue.",
                            operationType, amount, threshold));
        }

        // Check AML verification for higher amounts
        KYCSetting amlSetting = kycSettingDao.getKYCSetting("AML");
        if (amlSetting != null) {
            double amlThreshold = getThresholdForOperation(amlSetting, operationType);
            if (amount >= amlThreshold && !userVerify.isAmlVerified()) {
                throw new VerificationRequiredException(
                        String.format(
                                "Enhanced AML verification required for %s of $%.2f (threshold: $%.2f). Please contact support for additional verification.",
                                operationType, amount, amlThreshold));
            }
        }

        log.info("Verification requirements satisfied for user {} operation {} amount {}",
                user.getId(), operationType, amount);
    }

    /**
     * Check if country is blocked - uses database cache
     */
    @Cacheable(value = "blockedCountries", key = "#countryCode")
    public boolean isCountryBlocked(String countryCode) {
        try {
            Set<String> blockedCountries = getBlockedCountriesFromDB();
            boolean blocked = blockedCountries.contains(countryCode.toUpperCase());

            if (blocked) {
                CountrySetting setting = countrySettingDao.getCountrySettingByCode(countryCode);
                log.warn("Access blocked for country {}: {}", countryCode,
                        setting != null ? setting.getBlockReason() : "Unknown reason");
            }

            return blocked;
        } catch (Exception e) {
            log.error("Error checking blocked status for country {}: {}", countryCode, e.getMessage());
            // Default to not blocked on error to avoid blocking legitimate users
            return false;
        }
    }

    /**
     * Check if country is exempt from verification - uses database cache
     */
    @Cacheable(value = "exemptCountries", key = "#countryCode")
    public boolean isCountryExemptFromVerification(String countryCode) {
        try {
            Set<String> exemptCountries = getExemptCountriesFromDB();
            return exemptCountries.contains(countryCode.toUpperCase());
        } catch (Exception e) {
            log.error("Error checking exempt status for country {}: {}", countryCode, e.getMessage());
            // Default to not exempt on error for security
            return false;
        }
    }

    /**
     * Get blocked countries from database
     */
    private Set<String> getBlockedCountriesFromDB() {
        List<String> blocked = countrySettingDao.getBlockedCountries();
        return blocked.stream().collect(Collectors.toSet());
    }

    /**
     * Get verification exempt countries from database
     */
    private Set<String> getExemptCountriesFromDB() {
        List<String> exempt = countrySettingDao.getVerificationExemptCountries();
        return exempt.stream().collect(Collectors.toSet());
    }

    /**
     * Get KYC threshold for operation type
     */
    private double getKycThresholdForOperation(String operationType) {
        KYCSetting kycSetting = kycSettingDao.getKYCSetting("KYC");
        if (kycSetting == null)
            return 0.0;
        return getThresholdForOperation(kycSetting, operationType);
    }

    /**
     * Get threshold amount for specific operation type
     */
    private double getThresholdForOperation(KYCSetting setting, String operationType) {
        switch (operationType.toLowerCase()) {
            case "deposit":
                return setting.getDeposit();
            case "withdraw":
            case "withdrawal":
                return setting.getWithdraw();
            case "bid":
            case "auction":
                return setting.getBid();
            case "direct":
            case "purchase":
            case "buy":
                return setting.getDirect();
            default:
                log.warn("Unknown operation type: {}, defaulting to 0 threshold", operationType);
                return 0.0; // Default to requiring verification
        }
    }

    /**
     * Check if user's verification status is sufficient
     */
    public boolean isUserVerified(int userId) {
        UserVerify userVerify = userVerifyDao.selectById(userId);
        return userVerify != null && userVerify.isKycVerified();
    }

    /**
     * Get all countries with their settings and user counts
     */
    public List<java.util.Map<String, Object>> getAllCountriesWithSettings() {
        return countrySettingDao.getCountriesWithUserCount();
    }

    /**
     * Update country settings
     */
    public boolean updateCountrySetting(String countryCode, boolean verificationExempt, boolean blocked,
            String blockReason, String notes, int updatedBy) {
        try {
            CountrySetting setting = new CountrySetting();
            setting.setCountryCode(countryCode.toUpperCase());
            setting.setVerificationExempt(verificationExempt);
            setting.setBlocked(blocked);
            setting.setBlockReason(blockReason);
            setting.setNotes(notes);
            setting.setUpdatedBy(updatedBy);
            setting.setCreatedBy(updatedBy); // In case it's a new record

            int result = countrySettingDao.insertOrUpdateCountrySetting(setting);

            // Clear cache after update
            clearCountryCache(countryCode);

            return result > 0;
        } catch (Exception e) {
            log.error("Error updating country setting for {}: {}", countryCode, e.getMessage());
            return false;
        }
    }

    /**
     * Clear country cache after updates
     */
    private void clearCountryCache(String countryCode) {
        // Implementation depends on your cache manager
        // This would clear the specific country from cache
        log.info("Cache cleared for country: {}", countryCode);
    }
}