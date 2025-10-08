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

                userDetails = (UserDetailsImpl) userDetailsService.loadUserByUsername(email);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);
                logger.debug("Set authentication in security context for user: {}", email);
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication in security context", e);
        }

        // Handle location verification logic for root path
        if ("/".equals(requestPath)) {
            try {
                String ip = RemoteIpHelper.getRemoteIpFrom(request);
                HttpSession session = request.getSession();

                if (session.getAttribute(SESSION_IP) == null) {
                    logger.debug("Location verification for IP {} on path: {}", ip, requestPath);

                    LocationLog locationLog = null;
                    if (locationMap.containsKey(ip)) {
                        locationLog = locationMap.get(ip);
                        logger.debug("Found cached location for IP: {}", ip);
                    } else {
                        locationLog = locationLogService.buildLog(ip);
                        if (locationLog != null) {
                            locationMap.put(ip, locationLog);
                            logger.debug("Cached location for IP: {}", ip);
                        }
                    }

                    if (locationLog == null || locationLog.getCountry() == null) {
                        logger.warn("Location verification failed for IP: {}", ip);

                        JsonObject responseObject = new JsonObject();
                        responseObject.addProperty("status", "error");
                        responseObject.addProperty("message", "Unauthorized location");
                        responseObject.addProperty("ip", ip);

                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write(responseObject.toString());
                        return;
                    } else {
                        session.setAttribute(SESSION_IP, ip);
                        logger.debug("Location verification passed for IP: {} in country: {}", ip,
                                locationLog.getCountry());

                        JsonObject responseObject = new JsonObject();
                        responseObject.addProperty("status", "success");
                        responseObject.addProperty("message", "API is running - location check passed");
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

        // Note: We REMOVED the GraphQL endpoints from this exclusion list
        // so JWT processing will happen for GraphQL requests
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