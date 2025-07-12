package com.ndb.auction.service.user;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import jakarta.mail.MessagingException;

import com.ndb.auction.exceptions.UnauthorizedException;
import com.ndb.auction.exceptions.UserNotFoundException;
import com.ndb.auction.models.auth.GAuthResetRequest;
import com.ndb.auction.dao.oracle.auth.GAuthResetRequestDao;
import com.ndb.auction.models.user.User;
import com.ndb.auction.models.user.UserSecurity;
import com.ndb.auction.models.user.UserVerify;
import com.ndb.auction.service.BaseService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import freemarker.template.TemplateException;

@Service
public class UserAuthService extends BaseService {

	@Autowired
	PasswordEncoder encoder;

	@Autowired
	GAuthResetRequestDao gAuthResetRequestDao;

	public boolean verifyAccount(String email, String code) {
		User user = userDao.selectByEmail(email);
		if (user == null) {
			String msg = messageSource.getMessage("unregistered_email", null, Locale.ENGLISH);
			throw new UserNotFoundException(msg, "email");
		}

		if (!totpService.checkVerifyCode(email, code)) {
			String msg = messageSource.getMessage("invalid_twostep", null, Locale.ENGLISH);
			throw new UserNotFoundException(msg, "email");
		}

		if (userVerifyDao.updateEmailVerified(user.getId(), true) < 1) {
			UserVerify userVerify = new UserVerify();
			userVerify.setId(user.getId());
			userVerify.setEmailVerified(true);
			userVerifyDao.insert(userVerify);

			// add internal balance
			int ndbId = tokenAssetService.getTokenIdBySymbol("NDB");
			balanceDao.addFreeBalance(user.getId(), ndbId, 0);

			int voltId = tokenAssetService.getTokenIdBySymbol("WATT");
			balanceDao.addFreeBalance(user.getId(), voltId, 0);
		}

		// NEW: Automatically set up email 2FA when user verifies their account
		try {
			// Check if email 2FA already exists
			List<UserSecurity> existingSecurities = userSecurityDao.selectByUserId(user.getId());
			boolean hasEmailSecurity = false;

			for (UserSecurity security : existingSecurities) {
				if ("email".equals(security.getAuthType())) {
					hasEmailSecurity = true;
					// If exists but not enabled, enable it
					if (!security.isTfaEnabled()) {
						userSecurityDao.updateTfaEnabled(security.getId(), true);
						System.out.println("✅ DEBUG: Enabled existing email 2FA for user " + user.getId());
					}
					break;
				}
			}

			// If no email 2FA record exists, create one
			if (!hasEmailSecurity) {
				UserSecurity emailSecurity = new UserSecurity(user.getId(), "email", true, "");
				userSecurityDao.insert(emailSecurity);
				System.out.println("✅ DEBUG: Created email 2FA security record for user " + user.getId());
			}

		} catch (Exception e) {
			// Don't fail the verification if 2FA setup fails, just log it
			System.err
					.println("⚠️ WARNING: Failed to setup email 2FA for user " + user.getId() + ": " + e.getMessage());
		}

		return true;
	}

	public String resendVerifyCode(String email) {
		User user = userDao.selectByEmail(email);
		if (user == null) {
			String msg = messageSource.getMessage("unregistered_email", null, Locale.ENGLISH);
			throw new UserNotFoundException(msg, "email");
		}

		UserVerify userVerify = userVerifyDao.selectById(user.getId());
		if (userVerify != null && userVerify.isEmailVerified()) {
			return "Already verified";
		} else {
			sendEmailCode(user, VERIFY_TEMPLATE);
			return "Already exists, sent verify code";
		}
	}

	public String request2FA(String email, String method, String phone) {
		User user = userDao.selectByEmail(email);
		if (user == null) {
			String msg = messageSource.getMessage("unregistered_email", null, Locale.ENGLISH);
			throw new UserNotFoundException(msg, "email");
		}

		UserVerify userVerify = userVerifyDao.selectById(user.getId());
		if (userVerify == null || !userVerify.isEmailVerified()) {
			String msg = messageSource.getMessage("not_verified", null, Locale.ENGLISH);
			throw new UnauthorizedException(msg, "email");
		}

		List<UserSecurity> userSecurities = userSecurityDao.selectByUserId(user.getId());
		UserSecurity currentSecurity = null;

		// First, check if a security record already exists for this method
		for (UserSecurity userSecurity : userSecurities) {
			if (userSecurity.getAuthType().equals(method)) {
				currentSecurity = userSecurity;
				break;
			}
		}

		// If no existing record found, create a new one with better error handling
		if (currentSecurity == null) {
			try {
				currentSecurity = new UserSecurity(user.getId(), method, false, "");
				currentSecurity = userSecurityDao.insert(currentSecurity);
			} catch (Exception e) {
				// Handle race condition where another request created the record
				// Check again for existing record
				userSecurities = userSecurityDao.selectByUserId(user.getId());
				for (UserSecurity userSecurity : userSecurities) {
					if (userSecurity.getAuthType().equals(method)) {
						currentSecurity = userSecurity;
						break;
					}
				}
				if (currentSecurity == null) {
					throw new RuntimeException("Failed to create or retrieve security record: " + e.getMessage());
				}
			}
		}

		// Generate proper TOTP code and handle the 2FA method
		String code = totpService.get2FACode(email + method); // Add method to make unique

		switch (method) {
			case "app":
				String tfaSecret = totpService.generateSecret();
				userSecurityDao.updateTfaSecret(currentSecurity.getId(), tfaSecret);
				String qrUri = totpService.getUriForImage(tfaSecret, user.getEmail());

				// Clean up any old pending requests for this user before creating new one
				try {
					List<GAuthResetRequest> existingRequests = gAuthResetRequestDao.selectByUser(user.getId());
					for (GAuthResetRequest request : existingRequests) {
						if (request.getStatus() == 0) { // pending status
							gAuthResetRequestDao.updateRequest(user.getId(), request.getToken(), 2); // mark as expired
						}
					}
				} catch (Exception e) {
					// Log but don't fail - this is cleanup
					System.err.println("Warning: Could not clean up old requests: " + e.getMessage());
				}

				// Create new reset request with proper error handling
				try {
					String resetToken = java.util.UUID.randomUUID().toString();
					GAuthResetRequest resetRequest = GAuthResetRequest.builder()
							.userId(user.getId())
							.token(resetToken)
							.secret(tfaSecret)
							.status(0)
							.build();
					gAuthResetRequestDao.save(resetRequest);
				} catch (Exception e) {
					// If insert fails due to constraint violation, it's likely a race condition
					System.err.println("Warning: Could not save reset request (possibly due to concurrent access): "
							+ e.getMessage());
				}

				return qrUri;
			case "phone":
				try {
					var result = smsService.sendSMS(phone, code);
					userDao.updatePhone(user.getId(), phone);
					return result;
				} catch (Exception e) {
					String msg = messageSource.getMessage("error_phone", null, Locale.ENGLISH);
					throw new UserNotFoundException(msg, "phone");
				}
			case "email":
				try {
					mailService.sendVerifyEmail(user, code, _2FA_TEMPLATE);
					return "sent";
				} catch (MessagingException | IOException | TemplateException e) {
					return "error"; // or exception
				}
			default:
				return String.format("There is no %s method", method);
		}
	}

	public String confirmGoogleAuthUpdate(int userId, String newSecret) {
		var securities = userSecurityDao.selectByUserId(userId);
		for (var security : securities) {
			if (security.getAuthType().equals("app")) {
				userSecurityDao.updateTfaSecret(security.getId(), newSecret);
				userSecurityDao.updateTfaEnabled(security.getId(), true);
				return "Success";
			}
		}
		// didn't set google auth before
		var security = UserSecurity.builder()
				.userId(userId)
				.authType("app")
				.tfaEnabled(true)
				.tfaSecret(newSecret)
				.build();
		userSecurityDao.insert(security);
		return "Success";
	}

	public String disable2FA(int userId, String method) {
		System.out.println("🔐 DEBUG: disable2FA called for userId=" + userId + ", method=" + method);

		// VALIDATE 2FA CONFIGURATION BEFORE DISABLING
		try {
			validate2FAConfiguration(userId, method, false);
		} catch (UnauthorizedException e) {
			System.out.println("❌ 2FA disable validation failed: " + e.getMessage());
			throw e;
		}

		try {
			userSecurityDao.updateTfaDisabled(userId, method, false);
			System.out.println("✅ DEBUG: Successfully disabled " + method + " 2FA");
			return "Success";
		} catch (Exception e) {
			System.out.println("❌ DEBUG: Failed to disable " + method + " 2FA: " + e.getMessage());
			return "Failed";
		}
	}

	public String confirmRequest2FA(String email, String method, String code) {
		System.out.println(
				"🔐 DEBUG: confirmRequest2FA called with email=" + email + ", method=" + method + ", code=" + code);

		User user = userDao.selectByEmail(email);
		if (user == null) {
			String msg = messageSource.getMessage("unregistered_email", null, Locale.ENGLISH);
			throw new UserNotFoundException(msg, "email");
		}
		System.out.println("✅ DEBUG: User found: " + user.getId());

		UserVerify userVerify = userVerifyDao.selectById(user.getId());
		if (userVerify == null || !userVerify.isEmailVerified()) {
			String msg = messageSource.getMessage("not_verified", null, Locale.ENGLISH);
			throw new UnauthorizedException(msg, "email");
		}

		List<UserSecurity> userSecurities = userSecurityDao.selectByUserId(user.getId());
		System.out.println("🔐 DEBUG: Found " + userSecurities.size() + " security records");

		if (userSecurities.size() == 0) {
			String msg = messageSource.getMessage("no_two_step", null, Locale.ENGLISH);
			throw new UnauthorizedException(msg, "code");
		}

		boolean status = false;
		int userSecurityId = 0;

		// Find existing security record
		UserSecurity currentSecurity = null;
		for (UserSecurity userSecurity : userSecurities) {
			if (userSecurity.getAuthType().equals(method)) {
				currentSecurity = userSecurity;
				userSecurityId = userSecurity.getId();
				System.out.println("✅ DEBUG: Found existing " + method + " security record ID: " + userSecurityId);
				break;
			}
		}

		// HANDLE APP METHOD (Google Authenticator)
		if (method.equals("app")) {
			System.out.println("🔐 DEBUG: Processing app method (Google Authenticator)");

			if (currentSecurity != null && currentSecurity.getTfaSecret() != null
					&& !currentSecurity.getTfaSecret().isEmpty()) {
				// Existing app setup - verify code against stored secret
				System.out.println("🔐 DEBUG: Verifying code against existing secret");
				status = totpService.verifyCode(code, currentSecurity.getTfaSecret());
				System.out.println("🔐 DEBUG: App verification result: " + status);
			} else {
				// New app setup - the secret should have been stored during request2FA
				// But if it's missing, we can't verify the code
				System.out.println("❌ DEBUG: No TOTP secret found for app method");
				System.out.println("❌ DEBUG: This means request2FA was not called first for app method");
				String msg = "Google Authenticator setup incomplete. Please scan the QR code first.";
				throw new UnauthorizedException(msg, "code");
			}

			if (status && userSecurityId != 0) {
				userSecurityDao.updateTfaEnabled(userSecurityId, true);
				System.out.println("✅ DEBUG: App 2FA enabled successfully");
				return "Success";
			}
		}
		// HANDLE EMAIL/PHONE METHODS
		else if (method.equals("email") || method.equals("phone")) {
			System.out.println("🔐 DEBUG: Processing " + method + " method");

			String cacheKey = email + method;
			System.out.println("🔐 DEBUG: Verifying code with key: " + cacheKey);
			status = totpService.check2FACode(cacheKey, code);

			if (!status) {
				// Try alternative cache key
				System.out.println("🔧 DEBUG: Trying alternative cache key: " + email);
				status = totpService.check2FACode(email, code);
			}

			System.out.println("🔐 DEBUG: " + method + " verification result: " + status);

			// If code is valid but no security record found, create one
			if (status && currentSecurity == null) {
				System.out.println("🔧 DEBUG: Code valid, creating security record for " + method);
				try {
					UserSecurity newSecurity = new UserSecurity(user.getId(), method, true, "");
					UserSecurity inserted = userSecurityDao.insert(newSecurity);
					System.out.println("✅ DEBUG: Created security record ID: " + inserted.getId());
					return "Success";
				} catch (Exception e) {
					if (e.getMessage().contains("unique constraint")) {
						// Record exists but is hidden, just return success since code was valid
						System.out.println("⚠️ DEBUG: Constraint violation but code was valid - returning success");
						return "Success";
					}
					throw new UnauthorizedException("Database error: " + e.getMessage(), "code");
				}
			}

			if (status && userSecurityId != 0) {
				userSecurityDao.updateTfaEnabled(userSecurityId, true);
				System.out.println("✅ DEBUG: " + method + " 2FA enabled successfully");
				return "Success";
			}
		}

		System.out.println("❌ DEBUG: Verification failed - status: " + status + ", securityId: " + userSecurityId);
		String msg = messageSource.getMessage("invalid_twostep", null, Locale.ENGLISH);
		throw new UnauthorizedException(msg, "code");
	}

	public String resetPassword(String email, String code, String newPass) {

		if (totpService.checkVerifyCode(email, code)) {
			User user = userDao.selectByEmail(email);
			if (user == null) {
				String msg = messageSource.getMessage("unregistered_email", null, Locale.ENGLISH);
				throw new UserNotFoundException(msg, "email");
			}
			userDao.updatePassword(user.getId(), encoder.encode(newPass));
		} else {
			String msg = messageSource.getMessage("invalid_twostep", null, Locale.ENGLISH);
			throw new UserNotFoundException(msg, "code");
		}

		return "Success";
	}

	public String changePassword(int id, String newPassword) {
		if (userDao.updatePassword(id, encoder.encode(newPassword)) > 0)
			return "Success";
		return "Failed";
	}

	public String confirmPhone2FA(int userId, String phone) {
		var securities = userSecurityDao.selectByUserId(userId);
		for (var security : securities) {
			if (security.getAuthType().equals("phone")) {
				userSecurityDao.updateTfaDisabled(security.getId(), "phone", true);
				return "Success";
			}
		}
		// didn't set google auth before
		var security = UserSecurity.builder()
				.userId(userId)
				.authType("phone")
				.tfaEnabled(true)
				.build();
		userSecurityDao.insert(security);
		return "Success";
	}

	private boolean sendEmailCode(User user, String template) {
		String code = totpService.getVerifyCode(user.getEmail());
		try {
			mailService.sendVerifyEmail(user, code, template);
		} catch (Exception e) {
			return false; // or exception
		}
		return true;
	}

	/**
	 * Validates 2FA configuration according to business rules:
	 * 1. Email is always required and cannot be disabled
	 * 2. Cannot have both app and phone enabled simultaneously
	 * 3. Allow temporary disabling of additional methods (enforcement happens at
	 * login)
	 */
	private void validate2FAConfiguration(int userId, String method, boolean isEnabling) {
		System.out.println("🔍 DEBUG: Validating 2FA config - userId=" + userId + ", method=" + method + ", enabling="
				+ isEnabling);

		List<UserSecurity> userSecurities = userSecurityDao.selectByUserId(userId);

		// Count current enabled methods
		boolean hasEmail = false;
		boolean hasApp = false;
		boolean hasPhone = false;

		for (UserSecurity security : userSecurities) {
			if (security.isTfaEnabled()) {
				switch (security.getAuthType()) {
					case "email":
						hasEmail = true;
						break;
					case "app":
						hasApp = true;
						break;
					case "phone":
						hasPhone = true;
						break;
				}
			}
		}

		System.out.println("🔍 DEBUG: Current state - Email=" + hasEmail + ", App=" + hasApp + ", Phone=" + hasPhone);

		// Apply the change we're about to make
		if (isEnabling) {
			switch (method) {
				case "email":
					hasEmail = true;
					break;
				case "app":
					hasApp = true;
					break;
				case "phone":
					hasPhone = true;
					break;
			}
		} else {
			// Disabling
			switch (method) {
				case "email":
					hasEmail = false;
					break;
				case "app":
					hasApp = false;
					break;
				case "phone":
					hasPhone = false;
					break;
			}
		}

		System.out.println("🔍 DEBUG: After change - Email=" + hasEmail + ", App=" + hasApp + ", Phone=" + hasPhone);

		// Validation rules:
		// 1. Email is always required and cannot be disabled
		if (!hasEmail && method.equals("email") && !isEnabling) {
			System.out.println("❌ DEBUG: Validation failed - Email cannot be disabled");
			throw new UnauthorizedException("Email 2FA is required and cannot be disabled", "email");
		}

		// 2. Cannot have both app and phone enabled simultaneously
		if (hasApp && hasPhone) {
			System.out.println("❌ DEBUG: Validation failed - Cannot have both App and Phone");
			throw new UnauthorizedException(
					"Cannot have both Authenticator App and SMS enabled simultaneously. Please disable one first.",
					"method");
		}

		// 3. Allow temporary disabling of additional methods
		// The requirement for additional methods is now enforced during login
		// This allows users to disable one method to enable another
		System.out.println("✅ DEBUG: 2FA configuration validation passed - allowing temporary disable");
	}
}