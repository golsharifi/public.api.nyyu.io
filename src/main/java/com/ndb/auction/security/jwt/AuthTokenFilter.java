package com.ndb.auction.security.jwt;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.google.gson.JsonObject;
import com.ndb.auction.models.LocationLog;
import com.ndb.auction.service.LocationLogService;
import com.ndb.auction.service.user.UserDetailsImpl;
import com.ndb.auction.service.user.UserDetailsServiceImpl;
import com.ndb.auction.utils.RemoteIpHelper;

public class AuthTokenFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private LocationLogService locationLogService;

    private static final Logger logger = LoggerFactory.getLogger(AuthTokenFilter.class);

    private static final String SESSION_IP = "ip";

    private static final Map<String, LocationLog> locationMap = new HashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestPath = request.getServletPath();
        logger.debug("AuthTokenFilter processing path: {}", requestPath);

        UserDetailsImpl userDetails = null;
        try {
            String jwt = parseJwt(request);
            if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
                String email = jwtUtils.getEmailFromJwtToken(jwt);

                userDetails = userDetailsService.loadUserByUsername(email);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
                logger.debug("Authentication set for user: {}", email);
            } else {
                logger.debug("No valid JWT token found for path: {}", requestPath);
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication: {}", e.getMessage());
        }

        // Handle location verification for root path
        if ("/".equals(requestPath)) {
            try {
                String ip = RemoteIpHelper.getRemoteIpFrom(request);
                HttpSession session = request.getSession();
                String sessionIp = (String) session.getAttribute(SESSION_IP);

                logger.debug("Root path request - IP: {}, Session IP: {}", ip, sessionIp);

                if (sessionIp == null || !sessionIp.equals(ip)) {
                    logger.debug("New IP {} detected, performing location check", ip);
                    session.setAttribute(SESSION_IP, ip);

                    LocationLog location = locationMap.get(ip);
                    if (location == null) {
                        // Build location log using the correct method
                        location = locationLogService.buildLog(ip);
                        if (location != null) {
                            if (userDetails != null) {
                                location.setUserId(userDetails.getId());
                            }
                            // Save the location log using the correct method
                            location = locationLogService.addLog(location);
                            locationMap.put(ip, location);
                        }
                    }

                    if (location != null) {
                        // Check for VPN/Proxy using the correct method
                        if (locationLogService.isProxyOrVPN(location)) {
                            JsonObject responseObject = new JsonObject();
                            responseObject.addProperty("status", "blocked");
                            responseObject.addProperty("message", "Access denied - VPN/Proxy detected");
                            responseObject.addProperty("ip", ip);
                            responseObject.addProperty("country", location.getCountry());
                            responseObject.addProperty("countryCode", location.getCountryCode());

                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(responseObject.toString());
                            return;
                        } else {
                            JsonObject responseObject = new JsonObject();
                            responseObject.addProperty("status", "success");
                            responseObject.addProperty("message", "API is running - location check passed");
                            responseObject.addProperty("ip", ip);
                            responseObject.addProperty("country", location.getCountry());
                            responseObject.addProperty("countryCode", location.getCountryCode());

                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(responseObject.toString());
                            return;
                        }
                    } else {
                        // Handle case where location could not be determined
                        JsonObject responseObject = new JsonObject();
                        responseObject.addProperty("status", "success");
                        responseObject.addProperty("message", "API is running - location check skipped");
                        responseObject.addProperty("ip", ip);

                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write(responseObject.toString());
                        return;
                    }
                } else {
                    logger.debug("IP {} already verified in session for path: {}", ip, requestPath);
                    if ("/".equals(requestPath)) {
                        JsonObject responseObject = new JsonObject();
                        responseObject.addProperty("status", "success");
                        responseObject.addProperty("message", "API is running - location check passed (cached)");

                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write(responseObject.toString());
                        return;
                    }
                }
            } catch (Exception e) {
                logger.error("Error in location checking for path {}: {}", requestPath, e.getMessage());
                e.printStackTrace();
            }
        }

        logger.debug("Continuing filter chain for path: {}", requestPath);
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getServletPath();
        AntPathMatcher matcher = new AntPathMatcher();

        logger.debug("AuthTokenFilter checking path: {}", path);

        boolean shouldSkip =
                // Location and basic endpoints
                matcher.match("/api/location", path) ||
                        matcher.match("/location", path) ||
                        matcher.match("/favicon.ico", path) ||
                        matcher.match("/error", path) ||

                        // Authentication and OAuth2
                        matcher.match("/oauth2/**", path) ||
                        matcher.match("/auth/**", path) ||

                        // GraphQL endpoints - REMOVED THIS TO ALLOW JWT PROCESSING
                        // matcher.match("/graphql/**", path) ||
                        // matcher.match("/graphql", path) ||

                        // GraphQL UI tools (keep these public)
                        matcher.match("/graphiql/**", path) ||
                        matcher.match("/playground/**", path) ||

                        // Health and monitoring
                        matcher.match("/health", path) ||
                        matcher.match("/actuator/**", path) ||

                        // Static resources
                        matcher.match("/vendor/**", path) ||

                        // Payment and external webhooks
                        matcher.match("/shufti/**", path) ||
                        matcher.match("/stripe/**", path) ||
                        matcher.match("/paypal/**", path) ||
                        matcher.match("/crypto/**", path) ||
                        matcher.match("/ipn/**", path) ||
                        matcher.match("/nyyupay/**", path) ||

                        // Public API endpoints
                        matcher.match("/totalsupply/**", path) ||
                        matcher.match("/circulatingsupply/**", path) ||
                        matcher.match("/marketcap/**", path) ||
                        matcher.match("/ndbcoin/**", path) ||

                        // Social and Discord
                        matcher.match("/social/discord/**", path) ||

                        // Admin endpoints (if they should be public)
                        matcher.match("/admin/**", path) ||

                        // WebSocket
                        matcher.match("/ws/**", path);

        if (shouldSkip) {
            logger.debug("Skipping AuthTokenFilter for path: {}", path);
        }

        return shouldSkip;
    }

    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");

        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }

        return null;
    }
}