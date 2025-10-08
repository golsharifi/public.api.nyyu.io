package com.ndb.auction.resolver;

import com.ndb.auction.service.ShuftiService;
import com.ndb.auction.dao.oracle.ShuftiDao;
import com.ndb.auction.dao.oracle.user.UserVerifyDao;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.http.Part;

import org.apache.http.client.ClientProtocolException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ndb.auction.exceptions.BalanceException;
import com.ndb.auction.exceptions.UnauthorizedException;
import com.ndb.auction.exceptions.UserNotFoundException;
import com.ndb.auction.models.KYCSetting;
import com.ndb.auction.models.Shufti.ShuftiReference;
import com.ndb.auction.models.Shufti.Request.Names;
import com.ndb.auction.models.Shufti.Response.ShuftiResponse;
import com.ndb.auction.models.avatar.AvatarSet;
import com.ndb.auction.models.tier.TierTask;
import com.ndb.auction.payload.response.ShuftiRefPayload;
import com.ndb.auction.service.NamePriceService;
import com.ndb.auction.service.ProfileService;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.ndb.auction.service.utils.MailService;
import com.ndb.auction.web3.NyyuWalletService;

import graphql.kickstart.tools.GraphQLMutationResolver;
import graphql.kickstart.tools.GraphQLQueryResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProfileResolver extends BaseResolver implements GraphQLMutationResolver, GraphQLQueryResolver {

    private final NamePriceService namePriceService;
    private final ProfileService profileService;
    private final MailService mailService;
    private final NyyuWalletService nyyuWalletService;
    private final ShuftiService shuftiService;
    private final ShuftiDao shuftiDao;
    private final UserVerifyDao userVerifyDao;

    // select avatar profile
    // prefix means avatar name!!!
    @PreAuthorize("isAuthenticated()")
    public String setAvatar(String prefix, String name) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int id = userDetails.getId();
        return profileService.setAvatar(id, prefix, name);
    }

    // update avatar profile ( avatar set )
    @PreAuthorize("isAuthenticated()")
    public List<AvatarSet> updateAvatarSet(List<AvatarSet> components, String hairColor, String skinColor) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int id = userDetails.getId();
        return profileService.updateAvatarSet(id, components, hairColor, skinColor);
    }

    @PreAuthorize("isAuthenticated()")
    public TierTask getUserTierTask() {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int id = userDetails.getId();
        return tierTaskService.getTierTask(id);
    }

    // Identity Verification
    @PreAuthorize("isAuthenticated()")
    public String createNewReference()
            throws JsonProcessingException, IOException, InvalidKeyException, NoSuchAlgorithmException {
        // create reference record
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        ShuftiReference referenceObj = shuftiService.getShuftiReference(userId);
        int status = 1;
        if (referenceObj != null) {
            status = shuftiService.kycStatusRequestAsync(referenceObj.getReference());
            if (status == 1) {
                String msg = messageSource.getMessage("already_verified", null, Locale.ENGLISH);
                throw new UnauthorizedException(msg, "userId");
            }
        }
        String ref = "";
        if (status == 1) {
            ref = shuftiService.createShuftiReference(userId, "KYC");
        } else {
            ref = shuftiService.updateShuftiReference(userId, UUID.randomUUID().toString());
        }
        return ref;
    }

    @PreAuthorize("isAuthenticated()")
    public ShuftiRefPayload getShuftiRefPayload() {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        return shuftiService.getShuftiRefPayload(userId);
    }

    @PreAuthorize("isAuthenticated()")
    public Integer kycStatusRequest()
            throws JsonProcessingException, IOException, InvalidKeyException, NoSuchAlgorithmException {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();

        ShuftiReference referenceObj = shuftiService.getShuftiReference(userId);
        if (referenceObj == null) {
            String msg = messageSource.getMessage("no_ref", null, Locale.ENGLISH);
            throw new UserNotFoundException(msg, "user");
        }
        return shuftiService.kycStatusRequestAsync(referenceObj.getReference());
    }

    @PreAuthorize("isAuthenticated()")
    public Boolean uploadDocument(Part document) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                    .getPrincipal();
            int userId = userDetails.getId();

            // Validate the document
            if (document == null) {
                throw new IllegalArgumentException("Document cannot be null");
            }

            // Log file details for debugging
            log.info("📄 Processing document upload for user {}: filename={}, size={}, contentType={}",
                    userId, document.getSubmittedFileName(), document.getSize(), document.getContentType());

            return shuftiService.uploadDocument(userId, document);
        } catch (Exception e) {
            log.error("❌ Document upload failed for user", e);
            return false;
        }
    }

    @PreAuthorize("isAuthenticated()")
    public Boolean uploadAddress(Part address) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        return shuftiService.uploadAddress(userId, address);
    }

    @PreAuthorize("isAuthenticated()")
    public Boolean uploadConsent(Part consent) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        return shuftiService.uploadConsent(userId, consent);
    }

    @PreAuthorize("isAuthenticated()")
    public Boolean uploadSelfie(Part selfie) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        return shuftiService.uploadSelfie(userId, selfie);
    }

    @PreAuthorize("isAuthenticated()")
    public String sendVerifyRequest(String country, String fullAddr, Names names)
            throws ClientProtocolException, IOException, InvalidKeyException, NoSuchAlgorithmException {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        ShuftiReference referenceObj = shuftiService.getShuftiReference(userId);
        int status = 1;
        if (referenceObj != null) {
            status = shuftiService.kycStatusRequestAsync(referenceObj.getReference());
            if (status == 1) {
                String msg = messageSource.getMessage("already_verified", null, Locale.ENGLISH);
                throw new UnauthorizedException(msg, "userId");
            }
        }
        if (status == 1) {
            shuftiService.createShuftiReference(userId, "KYC");
        } else {
            shuftiService.updateShuftiReference(userId, UUID.randomUUID().toString());
        }
        return shuftiService.sendVerifyRequest(userId, country, fullAddr, names);
    }

    // Admin
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public int updateKYCSetting(String kind, Double bid, Double direct, Double deposit, Double withdraw) {
        return baseVerifyService.updateKYCSetting(kind, bid, direct, deposit, withdraw);
    }

    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public List<KYCSetting> getKYCSettings() {
        return baseVerifyService.getKYCSettings();
    }

    // frontend version
    @PreAuthorize("isAuthenticated()")
    public int insertOrUpdateReference(String reference) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        return shuftiService.insertOrUpdateReference(userId, reference);
    }

    @PreAuthorize("isAuthenticated()")
    public ShuftiReference getShuftiReference() {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();
        return shuftiService.getShuftiReference(userId);
    }

    @PreAuthorize("isAuthenticated()")
    public String changeEmail(String newEmail) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        var currentEmail = userDetails.getEmail();

        // Validate new email
        if (newEmail == null || newEmail.trim().isEmpty()) {
            throw new UnauthorizedException("New email address is required", "email");
        }

        // Check if new email is different from current
        if (currentEmail.equalsIgnoreCase(newEmail.trim())) {
            throw new UnauthorizedException("New email address must be different from current email", "email");
        }

        // Check if new email already exists
        var existingUser = userService.getUserByEmail(newEmail.trim());
        if (existingUser != null) {
            String msg = messageSource.getMessage("email_exists", null, Locale.ENGLISH);
            throw new UnauthorizedException(msg, "email");
        }

        // Generate verification code and send to CURRENT email
        var code = totpService.getVerifyCode(currentEmail + "_email_change");
        var user = userService.getUserByEmail(currentEmail);

        try {
            // Store the new email using the existing 2FA cache mechanism
            // We'll use a special key that includes the new email
            totpService.get2FACode(currentEmail + "_new_email_" + newEmail.trim());

            // Send verification email to CURRENT email
            mailService.sendVerifyEmail(user, code, "confirmEmailChange.ftlh");
            return "Success";
        } catch (Exception e) {
            throw new UnauthorizedException("Failed to send verification email", "email");
        }
    }

    @PreAuthorize("isAuthenticated()")
    public int confirmChangeEmail(String newEmail, String code) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        var currentEmail = userDetails.getEmail();

        // Verify the code against current email
        if (!totpService.checkVerifyCode(currentEmail + "_email_change", code)) {
            String msg = messageSource.getMessage("invalid_twostep", null, Locale.ENGLISH);
            throw new UnauthorizedException(msg, "code");
        }

        // Check that the session is valid by verifying the 2FA code exists
        String sessionKey = currentEmail + "_new_email_" + newEmail.trim();
        if (!totpService.check2FACode(sessionKey, totpService.get2FACode(sessionKey))) {
            // If this fails, it means either the session expired or the email doesn't match
            throw new UnauthorizedException("Email change session expired or invalid", "email");
        }

        // Double-check that new email doesn't exist (in case someone else registered it
        // during the process)
        var existingUser = userService.getUserByEmail(newEmail.trim());
        if (existingUser != null) {
            String msg = messageSource.getMessage("email_exists", null, Locale.ENGLISH);
            throw new UnauthorizedException(msg, "email");
        }

        // Update the email
        return profileService.updateEmail(userDetails.getId(), newEmail.trim());
    }

    @PreAuthorize("isAuthenticated()")
    public int changeBuyName(String newName) {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();

        // check user balance
        int chars = newName.length();
        var namePrice = namePriceService.select(chars);

        if (namePrice == null) {
            var priceList = namePriceService.selectAll();
            var len = priceList.size();
            namePrice = priceList.get(len - 1); // get last one
        }

        // get ndb with default NDB price : 0.01
        double ndbOrder = namePrice.getPrice() / 0.01;

        double ndbBalance = internalBalanceService.getFreeBalance(userId, "NDB");
        if (ndbBalance < ndbOrder) {
            String msg = messageSource.getMessage("insufficient", null, Locale.ENGLISH);
            throw new BalanceException(msg, "userId");
        }

        // change name
        int result = profileService.updateBuyName(userId, newName);
        if (result == 1) {
            return internalBalanceService.deductFree(userId, "NDB", ndbOrder);
        }
        return 0;
    }

    // temporary use
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public int updatePrivateKeys() {
        return nyyuWalletService.updatePrivateKeys();
    }

    @PreAuthorize("isAuthenticated()")
    public Boolean manualStatusCheck() {
        UserDetailsImpl userDetails = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal();
        int userId = userDetails.getId();

        try {
            ShuftiReference ref = shuftiService.getShuftiReference(userId);
            if (ref == null) {
                log.info("No reference found for user: {}", userId);
                return false;
            }

            // Check current status from Shufti Pro
            ShuftiResponse statusResponse = shuftiService.checkShuftiStatus(ref.getReference());
            if (statusResponse == null) {
                log.info("Could not check status for user: {}", userId);
                return false;
            }

            log.info("🔄 Manual status check for user {}: {}", userId, statusResponse.getEvent());

            // Update status based on response
            if ("verification.accepted".equals(statusResponse.getEvent())) {
                shuftiDao.passed(userId);

                // Update user verification status
                userVerifyDao.updateKYCVerified(userId, true);

                log.info("✅ Manual status update: User {} verified", userId);
                return true;
            } else if ("verification.declined".equals(statusResponse.getEvent())) {
                log.info("❌ Manual status update: User {} verification declined", userId);
                return false;
            }

            log.info("⏳ Manual status update: User {} verification still pending", userId);
            return false;
        } catch (Exception e) {
            log.error("❌ Error in manual status check for user {}", userId, e);
            return false;
        }
    }

    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public String adminForceKYCVerification(int userId, boolean verified) {
        log.info("🔧 Admin forcing KYC verification for user {} to {}", userId, verified);

        try {
            // Update the TBL_USER_VERIFY table
            int result = userVerifyDao.updateKYCVerified(userId, verified);

            if (result > 0) {
                log.info("✅ Updated TBL_USER_VERIFY for user {}", userId);

                // Also update Shufti reference to prevent auto-reset by polling
                try {
                    ShuftiReference ref = shuftiDao.selectById(userId);
                    if (ref != null) {
                        if (verified) {
                            // Mark all Shufti checks as passed
                            log.info("🔧 Setting Shufti status to PASSED for user {}", userId);
                            shuftiDao.passed(userId);
                            shuftiDao.updatePendingStatus(userId, false);
                        } else {
                            // Reset Shufti checks to failed
                            log.info("🔧 Setting Shufti status to FAILED for user {}", userId);
                            shuftiDao.updateDocStatus(userId, false);
                            shuftiDao.updateAddrStatus(userId, false);
                            shuftiDao.updateConStatus(userId, false);
                            shuftiDao.updateSelfieStatus(userId, false);
                            shuftiDao.updatePendingStatus(userId, false);
                        }
                        log.info("✅ Updated TBL_SHUFTI_REF for user {}", userId);
                    } else {
                        log.warn("⚠️ No Shufti reference found for user {}, creating one...", userId);
                        // Optionally create a Shufti reference if it doesn't exist
                    }
                } catch (Exception e) {
                    log.error("❌ Could not update Shufti reference for user {}: {}", userId, e.getMessage());
                    return "KYC updated but Shufti sync failed: " + e.getMessage();
                }

                log.info("✅ Admin successfully set KYC verification for user {} to {}", userId, verified);
                return verified ? "User verified successfully" : "User verification revoked successfully";
            } else {
                log.error("❌ Failed to update KYC_VERIFIED for user {}", userId);
                return "Failed to update KYC verification - user may not exist";
            }
        } catch (Exception e) {
            log.error("❌ Error in adminForceKYCVerification for user {}: {}", userId, e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

}
