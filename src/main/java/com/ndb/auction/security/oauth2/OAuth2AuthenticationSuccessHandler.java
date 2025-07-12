package com.ndb.auction.security.oauth2;

import com.ndb.auction.config.AppProperties;
import com.ndb.auction.security.TokenProvider;
import com.ndb.auction.security.jwt.JwtUtils;
import com.ndb.auction.service.CustomOAuth2UserService;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.ndb.auction.service.user.UserSecurityService;
import com.ndb.auction.service.user.UserService;
import com.ndb.auction.service.utils.TotpService;
import com.ndb.auction.exceptions.BadRequestException;
import com.ndb.auction.models.user.AuthProvider;
import com.ndb.auction.models.user.User;
import com.ndb.auction.models.user.UserSecurity;
import com.ndb.auction.utils.CookieUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.extern.slf4j.Slf4j;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ndb.auction.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_PARAM_COOKIE_NAME;

@Slf4j
@Component
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private TokenProvider tokenProvider;
    private AppProperties appProperties;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserService userService;

    @Autowired
    private UserSecurityService userSecurityService;

    @Autowired
    private TotpService totpService;

    @Autowired
    private CustomOAuth2UserService customOAuth2UserService;

    private HttpCookieOAuth2AuthorizationRequestRepository httpCookieOAuth2AuthorizationRequestRepository;

    @Autowired
    AuthenticationManager authenticationManager;

    public OAuth2AuthenticationSuccessHandler(TokenProvider tokenProvider, AppProperties appProperties,
            HttpCookieOAuth2AuthorizationRequestRepository httpCookieOAuth2AuthorizationRequestRepository) {
        this.tokenProvider = tokenProvider;
        this.appProperties = appProperties;
        this.httpCookieOAuth2AuthorizationRequestRepository = httpCookieOAuth2AuthorizationRequestRepository;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        String targetUrl = determineTargetUrl(request, response, authentication);

        if (response.isCommitted()) {
            logger.debug("Response has already been committed. Unable to redirect to " + targetUrl);
            return;
        }

        clearAuthenticationAttributes(request, response);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) {
        Optional<String> redirectUri = CookieUtils.getCookie(request, REDIRECT_URI_PARAM_COOKIE_NAME)
                .map(Cookie::getValue);

        // Add debug logging
        log.info("🔍 OAuth2 Debug - Redirect URI from cookie: {}", redirectUri.orElse("NONE"));
        log.info("🔍 OAuth2 Debug - Authorized redirect URIs: {}",
                appProperties.getOauth2().getAuthorizedRedirectUris());

        // Use fallback if redirect URI is empty or missing
        String targetUrl;
        if (redirectUri.isPresent() && !redirectUri.get().trim().isEmpty()) {
            if (!isAuthorizedRedirectUri(redirectUri.get())) {
                log.error("❌ Unauthorized redirect URI: {}. Authorized URIs: {}",
                        redirectUri.get(), appProperties.getOauth2().getAuthorizedRedirectUris());
                throw new BadRequestException(
                        "Sorry! We've got an Unauthorized Redirect URI and can't proceed with the authentication");
            }
            targetUrl = redirectUri.get();
        } else {
            // Fallback to configured frontend URL if cookie is empty
            targetUrl = appProperties.getOauth2().getAuthorizedRedirectUris().get(0);
            log.info("🔍 OAuth2 Debug - Using fallback redirect URI: {}", targetUrl);
        }

        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        String registrationId = oauthToken.getAuthorizedClientRegistrationId();

        log.info("targetURI : {} registrationID : {}, UserPrincipal {},", targetUrl, registrationId,
                authentication.getPrincipal());

        UserDetailsImpl userPrincipal = new UserDetailsImpl();

        try {
            userPrincipal = (UserDetailsImpl) authentication.getPrincipal();
        } catch (Exception e) {
            if (registrationId.equalsIgnoreCase(AuthProvider.apple.toString())) { // In case of Apple
                OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
                Map<String, Object> attributes = new HashMap<>(oAuth2User.getAttributes());
                userPrincipal = customOAuth2UserService.processUserDetails(registrationId, attributes);
            } else {
                return UriComponentsBuilder.fromUriString(targetUrl + "/error/unknown/registrationId").build()
                        .toUriString();
            }
        }

        User user = userService.getUserByEmail(userPrincipal.getEmail());

        String type = "success";
        String dataType;
        String data;

        boolean is2FA = false;
        List<UserSecurity> userSecurities = userSecurityService.selectByUserId(user.getId());
        for (UserSecurity security : userSecurities) {
            if (security.isTfaEnabled()) {
                is2FA = true;
                break;
            }
        }

        if (!user.getProvider().equals(registrationId)) {
            type = "error";
            dataType = "InvalidProvider";
            data = user.getProvider().toString();
        } else if (!is2FA) {
            type = "error";
            dataType = "No2FA";
            data = user.getEmail();
        } else {
            dataType = userService.signin2FA(user);
            data = user.getEmail();
            if (dataType.equals("error")) {
                return UriComponentsBuilder.fromUriString(targetUrl + "/error/Failed/2FA Error").build().toUriString();
            }
            // Save token on cache
            totpService.setTokenAuthCache(dataType, authentication);

            for (UserSecurity security : userSecurities) {
                if (security.isTfaEnabled())
                    data += "*" + security.getAuthType();
            }
        }

        // **CRITICAL FIX: Only create JWT token when 2FA is NOT required**
        String token = null;
        if (type.equals("success") && is2FA) {
            // 2FA is required - do NOT create JWT token yet
            // The dataType already contains the temporary 2FA token
            log.info("🔐 2FA required for user: {} - not creating JWT token yet", user.getEmail());
            log.info("🔐 Temporary 2FA token: {}", dataType);
            token = null; // No JWT token for 2FA flow
        } else if (type.equals("success") && !is2FA) {
            // No 2FA required - create JWT token for direct login
            try {
                token = tokenProvider.createToken(userPrincipal);
                log.info("🎫 Created JWT token for user (no 2FA): {}", user.getEmail());
            } catch (Exception e) {
                log.error("❌ Failed to create JWT token for user: {}", user.getEmail(), e);
                type = "error";
                dataType = "TokenCreationFailed";
                data = "Unable to create authentication token";
            }
        }
        // Add token to the URL only if it's a final JWT (not 2FA flow)
        String finalUrl;
        if (token != null) {
            // Full JWT token - direct login (no 2FA)
            finalUrl = UriComponentsBuilder.fromUriString(targetUrl + "/" + type + "/" + dataType + "/" + data)
                    .queryParam("token", token)
                    .build().toUriString();
            log.info("🔍 Redirect URL with JWT token: {}", finalUrl.replaceAll("token=[^&]*", "token=***"));
        } else {
            // No JWT token - either error or 2FA flow
            finalUrl = UriComponentsBuilder.fromUriString(targetUrl + "/" + type + "/" + dataType + "/" + data)
                    .build().toUriString();
            if (type.equals("success") && is2FA) {
                log.info("🔍 Redirect URL for 2FA flow (no JWT): {}", finalUrl);
            } else {
                log.info("🔍 Redirect URL for error: {}", finalUrl);
            }
        }

        return finalUrl;
    }

    protected void clearAuthenticationAttributes(HttpServletRequest request, HttpServletResponse response) {
        super.clearAuthenticationAttributes(request);
        httpCookieOAuth2AuthorizationRequestRepository.removeAuthorizationRequestCookies(request, response);
    }

    private boolean isAuthorizedRedirectUri(String uri) {
        URI clientRedirectUri = URI.create(uri);

        return appProperties.getOauth2().getAuthorizedRedirectUris()
                .stream()
                .anyMatch(authorizedRedirectUri -> {
                    // Only validate host and port. Let the clients use different paths if they want
                    // to
                    URI authorizedURI = URI.create(authorizedRedirectUri);
                    if (authorizedURI.getHost().equalsIgnoreCase(clientRedirectUri.getHost())
                            && authorizedURI.getPort() == clientRedirectUri.getPort()) {
                        return true;
                    }
                    return false;
                });
    }

}