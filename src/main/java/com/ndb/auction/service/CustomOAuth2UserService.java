package com.ndb.auction.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ndb.auction.dao.oracle.balance.CryptoBalanceDao;
import com.ndb.auction.dao.oracle.user.UserDao;
import com.ndb.auction.dao.oracle.user.UserVerifyDao;
import com.ndb.auction.exceptions.OAuth2AuthenticationProcessingException;
import com.ndb.auction.models.user.AuthProvider;
import com.ndb.auction.models.user.User;
import com.ndb.auction.models.user.UserVerify;
import com.ndb.auction.security.oauth2.user.OAuth2UserInfo;
import com.ndb.auction.security.oauth2.user.OAuth2UserInfoFactory;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.ndb.auction.service.user.UserService;
import com.ndb.auction.service.utils.MailService;
import com.ndb.auction.exceptions.ProviderMismatchException;
import com.ndb.auction.service.utils.TotpService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    public static final String RANDOM_PASSWORD = "randomPassword.ftlh";

    @Autowired
    private UserDao userDao;

    @Autowired
    private UserVerifyDao userVerifyDao;

    @Autowired
    private UserService userService;

    @Autowired
    public TotpService totpService;

    @Autowired
    public MailService mailService;

    @Autowired
    private CryptoBalanceDao balanceDao;

    @Autowired
    private TokenAssetService tokenAssetService;

    @Value("${linkedin.email-address-uri}")
    private String linkedInEmailEndpointUri;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest oAuth2UserRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(oAuth2UserRequest);
        log.info("oAuth2User Name: [{}], Granted Authorities: [{}], User Attributes: [{}]",
                oAuth2User.getName(), oAuth2User.getAuthorities(), oAuth2User.getAttributes());

        try {
            Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
            String provider = oAuth2UserRequest.getClientRegistration().getRegistrationId();

            // Handle LinkedIn-specific processing for API v2
            if (provider.equalsIgnoreCase(AuthProvider.linkedin.toString())) {
                populateEmailAddressFromLinkedIn(oAuth2UserRequest, attributes);
            }

            OAuth2User user = processUserDetails(provider, attributes);
            return user;
        } catch (AuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            // Throwing an instance of AuthenticationException will trigger the
            // OAuth2AuthenticationFailureHandler
            throw new InternalAuthenticationServiceException(ex.getMessage(), ex.getCause());
        }
    }

    /**
     * Updated method for LinkedIn API - handles the current API restrictions
     * gracefully
     * LinkedIn has severely limited API access and most endpoints require special
     * permissions
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void populateEmailAddressFromLinkedIn(OAuth2UserRequest oAuth2UserRequest, Map<String, Object> attributes)
            throws OAuth2AuthenticationException {

        log.info("Attempting to populate LinkedIn user data with current API restrictions");

        // First, check if we already have basic data from the OAuth2 token response
        if (attributes.containsKey("email") && attributes.get("email") != null) {
            log.info("Email already present in LinkedIn attributes: {}", attributes.get("email"));
            return;
        }

        String accessToken = oAuth2UserRequest.getAccessToken().getTokenValue();
        RestTemplate restTemplate = new RestTemplate();

        // Set up headers with authorization
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Accept", "application/json");

        HttpEntity<?> entity = new HttpEntity<>(headers);

        // Try to get profile information, but gracefully handle failures
        boolean profileSuccess = tryGetProfileWithFallback(restTemplate, entity, attributes);

        if (!profileSuccess) {
            log.warn("LinkedIn API access failed. Using fallback approach for user data.");
            // Create minimal user data from what we have
            createFallbackUserData(attributes, oAuth2UserRequest);
        }

        // Always ensure we have some form of email
        ensureEmailAvailable(attributes);
    }

    /**
     * Try to get profile information with graceful fallback
     */
    private boolean tryGetProfileWithFallback(RestTemplate restTemplate, HttpEntity<?> entity,
            Map<String, Object> attributes) {
        try {
            // Try the basic profile endpoint first
            String profileEndpoint = "https://api.linkedin.com/v2/people/~:(id,localizedFirstName,localizedLastName)";

            log.debug("Attempting LinkedIn profile request: {}", profileEndpoint);

            ResponseEntity<Map> response = restTemplate.exchange(
                    profileEndpoint,
                    HttpMethod.GET,
                    entity,
                    Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> profileData = response.getBody();
                log.debug("LinkedIn profile response: {}", profileData);

                // Process the profile data
                processLinkedInProfileData(profileData, attributes);

                // Try to get email separately
                tryGetEmailAddress(restTemplate, entity, attributes);

                log.info("LinkedIn profile data retrieved successfully");
                return true;
            } else {
                log.warn("LinkedIn profile request failed with status: {}", response.getStatusCode());
                return false;
            }

        } catch (Exception e) {
            log.warn("LinkedIn API call failed: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /**
     * Process LinkedIn profile data from API response
     */
    private void processLinkedInProfileData(Map<String, Object> profileData, Map<String, Object> attributes) {
        if (profileData.containsKey("id")) {
            attributes.put("id", profileData.get("id"));
            attributes.put("sub", profileData.get("id")); // For compatibility
            log.debug("LinkedIn ID: {}", profileData.get("id"));
        }

        // Handle localized names
        if (profileData.containsKey("localizedFirstName")) {
            Map<String, Object> firstNameMap = (Map<String, Object>) profileData.get("localizedFirstName");
            String firstName = extractLocalizedText(firstNameMap);
            if (firstName != null) {
                attributes.put("given_name", firstName);
                attributes.put("first_name", firstName);
                log.debug("LinkedIn first name: {}", firstName);
            }
        }

        if (profileData.containsKey("localizedLastName")) {
            Map<String, Object> lastNameMap = (Map<String, Object>) profileData.get("localizedLastName");
            String lastName = extractLocalizedText(lastNameMap);
            if (lastName != null) {
                attributes.put("family_name", lastName);
                attributes.put("last_name", lastName);
                log.debug("LinkedIn last name: {}", lastName);
            }
        }

        // Construct full name
        String firstName = (String) attributes.get("given_name");
        String lastName = (String) attributes.get("family_name");
        if (firstName != null || lastName != null) {
            String fullName = (firstName != null ? firstName : "") +
                    (firstName != null && lastName != null ? " " : "") +
                    (lastName != null ? lastName : "");
            attributes.put("name", fullName.trim());
            log.debug("LinkedIn full name: {}", fullName);
        }
    }

    /**
     * Create fallback user data when LinkedIn API is not accessible
     */
    private void createFallbackUserData(Map<String, Object> attributes, OAuth2UserRequest oAuth2UserRequest) {
        log.info("Creating fallback LinkedIn user data");

        // Generate a unique ID based on the OAuth2 token or client registration
        String fallbackId = "linkedin_" + System.currentTimeMillis();

        // Check if we can get any info from the OAuth2 token response
        OAuth2AccessToken accessToken = oAuth2UserRequest.getAccessToken();
        if (accessToken != null && accessToken.getTokenValue() != null) {
            // Use a hash of the token for a more consistent ID
            fallbackId = "linkedin_" + Math.abs(accessToken.getTokenValue().hashCode());
        }

        attributes.put("id", fallbackId);
        attributes.put("sub", fallbackId);

        // Set default name
        attributes.put("name", "LinkedIn User");
        attributes.put("given_name", "LinkedIn");
        attributes.put("family_name", "User");

        log.info("Created fallback LinkedIn user with ID: {}", fallbackId);
    }

    /**
     * Try to get email address - with fallback
     */
    private void tryGetEmailAddress(RestTemplate restTemplate, HttpEntity<?> entity, Map<String, Object> attributes) {
        try {
            String emailEndpoint = "https://api.linkedin.com/v2/emailAddress?q=members&projection=(elements*(handle~))";

            log.debug("Attempting LinkedIn email request: {}", emailEndpoint);

            ResponseEntity<Map> response = restTemplate.exchange(
                    emailEndpoint,
                    HttpMethod.GET,
                    entity,
                    Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> emailData = response.getBody();
                log.debug("LinkedIn email response: {}", emailData);

                if (emailData.containsKey("elements")) {
                    List<Map<String, Object>> elements = (List<Map<String, Object>>) emailData.get("elements");
                    if (!elements.isEmpty()) {
                        Map<String, Object> emailElement = elements.get(0);
                        if (emailElement.containsKey("handle~")) {
                            Map<String, Object> handle = (Map<String, Object>) emailElement.get("handle~");
                            if (handle.containsKey("emailAddress")) {
                                String email = (String) handle.get("emailAddress");
                                attributes.put("email", email);
                                log.info("LinkedIn email retrieved: {}", email);
                            }
                        }
                    }
                }
            } else {
                log.warn("LinkedIn email request failed with status: {}", response.getStatusCode());
            }

        } catch (Exception e) {
            log.warn("Could not retrieve LinkedIn email: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Ensure email is available - create fallback if needed
     */
    private void ensureEmailAvailable(Map<String, Object> attributes) {
        if (attributes.containsKey("email") && attributes.get("email") != null) {
            return; // Email already available
        }

        // Create a fallback email based on LinkedIn ID
        String linkedinId = (String) attributes.get("id");
        if (linkedinId != null) {
            String fallbackEmail = linkedinId + "@linkedin.placeholder.com";
            attributes.put("email", fallbackEmail);
            attributes.put("email_verified", false);
            attributes.put("email_requires_verification", true);

            log.warn("No email available from LinkedIn API. Created fallback email: {}", fallbackEmail);
            log.warn("User will need to provide their real email address after registration");
        }
    }

    /**
     * Extract email from OAuth2 attributes if available
     */
    private void extractEmailFromOAuth2Attributes(Map<String, Object> attributes) {
        // Sometimes email comes through in the initial OAuth2 flow
        if (attributes.containsKey("email") && attributes.get("email") != null) {
            log.info("Email found in OAuth2 attributes: {}", attributes.get("email"));
            return;
        }

        // Check for email in sub-attributes
        if (attributes.containsKey("contact") && attributes.get("contact") instanceof Map) {
            Map<String, Object> contact = (Map<String, Object>) attributes.get("contact");
            if (contact.containsKey("emailAddress")) {
                attributes.put("email", contact.get("emailAddress"));
                log.info("Email extracted from contact info: {}", contact.get("emailAddress"));
                return;
            }
        }
    }

    /**
     * Extract localized text from LinkedIn's localized format
     */
    private String extractLocalizedText(Map<String, Object> localizedMap) {
        if (localizedMap == null || localizedMap.isEmpty()) {
            return null;
        }

        // Try common locales first
        String[] preferredLocales = { "en_US", "en", "en_GB" };
        for (String locale : preferredLocales) {
            if (localizedMap.containsKey(locale)) {
                return (String) localizedMap.get(locale);
            }
        }

        // If no preferred locale, return the first available value
        return (String) localizedMap.values().iterator().next();
    }

    /**
     * Fallback method to handle legacy LinkedIn API response format
     * This maintains backward compatibility if the old format is somehow still
     * returned
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void parseLegacyLinkedInResponse(Map<String, Object> userInfo, Map<String, Object> attributes) {
        try {
            if (userInfo.containsKey("elements")) {
                List<Map<String, Object>> elements = (List<Map<String, Object>>) userInfo.get("elements");
                if (!elements.isEmpty()) {
                    Map<String, Object> emailElement = elements.get(0);
                    if (emailElement.containsKey("handle~")) {
                        Map<String, Object> handle = (Map<String, Object>) emailElement.get("handle~");
                        if (handle.containsKey("emailAddress")) {
                            String email = (String) handle.get("emailAddress");
                            attributes.put("email", email);
                            log.info("LinkedIn email retrieved from legacy format: {}", email);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing legacy LinkedIn response format: {}", e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    public UserDetailsImpl processUserDetails(String provider, Map<String, Object> attributes) {
        log.info("processUserDetails with provider: {} and attributes: {}", provider, attributes);

        OAuth2UserInfo oAuth2UserInfo = OAuth2UserInfoFactory.getOAuth2UserInfo(provider, attributes);

        if (StringUtils.isEmpty(oAuth2UserInfo.getEmail())) {
            log.error("Email not found from OAuth2 provider: {}. Available attributes: {}", provider,
                    attributes.keySet());
            throw new OAuth2AuthenticationProcessingException("Email not found from OAuth2 provider");
        }

        User user = userDao.selectByEmail(oAuth2UserInfo.getEmail());
        if (user != null) {
            // Previous signup type checking - FIXED provider comparison
            log.info("🔍 Provider comparison: stored='{}', current='{}', equals={}",
                    user.getProvider(), provider, isProviderMatch(user.getProvider(), provider));

            if (!isProviderMatch(user.getProvider(), provider)) {
                // doesn't match previous provider type!
                log.error("Provider mismatch for user {}: stored='{}', current='{}'",
                        oAuth2UserInfo.getEmail(), user.getProvider(), provider);
                throw new ProviderMismatchException(oAuth2UserInfo.getEmail(), user.getProvider(), provider);
            }
            user = updateExistingUser(user, oAuth2UserInfo);
        } else {
            user = registerNewUser(provider, oAuth2UserInfo);
        }

        return UserDetailsImpl.build(user, attributes);
    }

    /**
     * Compare provider values with proper handling of string/enum mismatches
     * This method handles cases where the stored provider might be in different
     * formats
     */
    private boolean isProviderMatch(String storedProvider, String currentProvider) {
        if (storedProvider == null || currentProvider == null) {
            return false;
        }

        // Direct string comparison first (fastest)
        if (storedProvider.equalsIgnoreCase(currentProvider)) {
            return true;
        }

        // Handle case where stored provider might be uppercase enum name
        // Convert both to lowercase for comparison
        String normalizedStored = storedProvider.toLowerCase().trim();
        String normalizedCurrent = currentProvider.toLowerCase().trim();

        return normalizedStored.equals(normalizedCurrent);
    }

    @Transactional
    protected User registerNewUser(String provider, OAuth2UserInfo oAuth2UserInfo) {
        log.info("Registering new user with provider: {}, email: {}", provider, oAuth2UserInfo.getEmail());

        try {
            User user = new User();
            UserVerify userVerify = new UserVerify();

            user.setProvider(provider.toLowerCase()); // Store in lowercase for consistency
            user.setProviderId(oAuth2UserInfo.getId());
            user.setName(oAuth2UserInfo.getName());
            user.setEmail(oAuth2UserInfo.getEmail());

            // Handle locale/country with null safety
            String locale = oAuth2UserInfo.getLocale();
            if (locale != null && !locale.isEmpty()) {
                user.setCountry(locale.toUpperCase());
            } else {
                user.setCountry("US"); // Default country
            }

            user.setNotifySetting(0xFFFF);
            Set<String> roles = new HashSet<String>();
            roles.add("ROLE_USER");
            user.setRole(roles);
            userVerify.setEmailVerified(true);

            String rPassword = userService.getRandomPassword(10);
            String encoded = userService.encodePassword(rPassword);
            user.setPassword(encoded);

            // Send Random Password to user - uncommented for production use
            // try {
            // mailService.sendVerifyEmail(user, rPassword, RANDOM_PASSWORD);
            // log.info("Welcome email sent to new user: {}", user.getEmail());
            // } catch (Exception e) {
            // log.warn("Failed to send welcome email to user: {}", user.getEmail(), e);
            // }

            user = userDao.insert(user);
            userVerify.setId(user.getId());
            userVerifyDao.insertOrUpdate(userVerify);

            // Add internal balance with error handling
            try {
                int ndbId = tokenAssetService.getTokenIdBySymbol("NDB");
                balanceDao.addFreeBalance(user.getId(), ndbId, 0);

                int voltId = tokenAssetService.getTokenIdBySymbol("WATT");
                balanceDao.addFreeBalance(user.getId(), voltId, 0);

                log.debug("Initial balances created for user: {}", user.getEmail());
            } catch (Exception e) {
                log.error("Failed to create initial balances for user: {}", user.getEmail(), e);
                // Don't fail registration, just log the error
            }

            log.info("Successfully registered new user: {}", user.getEmail());
            return user;
        } catch (Exception e) {
            log.error("Error registering new user with email: {}", oAuth2UserInfo.getEmail(), e);
            throw new OAuth2AuthenticationProcessingException("Failed to register new user", e);
        }
    }

    private User updateExistingUser(User existingUser, OAuth2UserInfo oAuth2UserInfo) {
        log.info("Updating existing user: {}", existingUser.getEmail());

        try {
            UserVerify userVerify = userVerifyDao.selectById(existingUser.getId());
            if (userVerify == null) {
                userVerify = new UserVerify();
            }

            userVerify.setId(existingUser.getId());
            userVerify.setEmailVerified(true);

            // Update user name if available
            String name = oAuth2UserInfo.getName();
            if (name != null && !name.trim().isEmpty()) {
                userDao.updateName(existingUser.getId(), name);
                log.debug("Updated name for user: {}", existingUser.getEmail());
            }

            userVerifyDao.insertOrUpdate(userVerify);

            log.info("Successfully updated existing user: {}", existingUser.getEmail());
            return existingUser;
        } catch (Exception e) {
            log.error("Error updating existing user: {}", existingUser.getEmail(), e);
            throw new OAuth2AuthenticationProcessingException("Failed to update existing user", e);
        }
    }
}