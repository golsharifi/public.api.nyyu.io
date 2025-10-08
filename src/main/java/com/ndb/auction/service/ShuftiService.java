package com.ndb.auction.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.http.Part;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ndb.auction.exceptions.UnauthorizedException;
import com.ndb.auction.models.Shufti.ShuftiReference;
import com.ndb.auction.models.Shufti.Request.Names;
import com.ndb.auction.models.Shufti.Request.ShuftiRequest;
import com.ndb.auction.models.Shufti.Response.ShuftiResponse;
import com.ndb.auction.payload.ShuftiStatusRequest;
import com.ndb.auction.payload.response.ShuftiRefPayload;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import lombok.extern.slf4j.Slf4j;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;

@Service
@Slf4j
public class ShuftiService extends BaseService {

    // Shuftipro URL
    private static final String BASE_URL = "https://api.shuftipro.com/";

    @Value("${shufti.client.id}")
    private String CLIENT_ID;

    @Value("${shufti.secret.key}")
    private String SECRET_KEY;

    @Value("${shufti.callback.url}")
    private String CALLBACK_URL;

    @Value("${azure.storage.container-name}")
    private String containerName;

    @Value("${shufti.manual.review:1}")
    private String manualReview;

    @Autowired
    private BlobServiceClient blobServiceClient;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    protected CloseableHttpClient client;

    public ShuftiService(WebClient.Builder webClientBuilder) {
        client = HttpClients.createDefault();
        // Removed unused shuftiAPI initialization
    }

    // Create a properly configured Gson instance
    private Gson createGson() {
        return new GsonBuilder()
                .setStrictness(Strictness.LENIENT)
                .create();
    }

    public boolean uploadDocument(int userId, Part document) {
        return uploadToBlobStorage(userId, document, "passport");
    }

    public boolean uploadAddress(int userId, Part address) {
        return uploadToBlobStorage(userId, address, "address");
    }

    public boolean uploadConsent(int userId, Part consent) {
        return uploadToBlobStorage(userId, consent, "consent");
    }

    public boolean uploadSelfie(int userId, Part selfie) {
        return uploadToBlobStorage(userId, selfie, "selfie");
    }

    private boolean uploadToBlobStorage(int userId, Part file, String documentType) {
        String blobName = userId + "-" + documentType;
        try {
            BlobClient blobClient = blobServiceClient.getBlobContainerClient(containerName)
                    .getBlobClient(blobName);

            InputStream inputStream = file.getInputStream();

            // Set content type based on file
            BlobHttpHeaders headers = new BlobHttpHeaders()
                    .setContentType(file.getContentType());

            blobClient.upload(inputStream, file.getSize(), true);
            blobClient.setHttpHeaders(headers);

            inputStream.close();
            log.info("Successfully uploaded {} to Azure Blob Storage", blobName);
            return true;
        } catch (Exception e) {
            log.error("Failed to upload {} to Azure Blob Storage", blobName, e);
            return false;
        }
    }

    public ShuftiRefPayload getShuftiRefPayload(int userId) {
        ShuftiReference ref = shuftiDao.selectById(userId);
        ShuftiRefPayload refPayload = new ShuftiRefPayload(ref);

        // download files
        refPayload.setDocument(downloadImage(userId, "passport"));
        refPayload.setAddr(downloadImage(userId, "address"));
        refPayload.setConsent(downloadImage(userId, "consent"));
        refPayload.setSelfie(downloadImage(userId, "selfie"));

        return refPayload;
    }

    public String sendVerifyRequest(int userId, String country, String fullAddr, Names names)
            throws ClientProtocolException, IOException {
        try {
            log.info("=== STARTING DOCUMENT VERIFICATION FOR USER: {} ===", userId);
            log.info("Country: {}, Address: {}, Names: {}", country, fullAddr, names);

            ShuftiReference refObj = shuftiDao.selectById(userId);
            if (refObj == null) {
                log.error("❌ No ShuftiReference found for user: {}", userId);
                String msg = messageSource.getMessage("no_ref", null, Locale.ENGLISH);
                throw new UnauthorizedException(msg, "reference");
            }

            String reference = refObj.getReference();
            log.info("✅ Using reference: {} for user: {}", reference, userId);

            // Download images with detailed logging
            log.info("📥 Downloading documents for user: {}", userId);
            String doc64 = downloadImage(userId, "passport");
            String addr64 = downloadImage(userId, "address");
            String con64 = downloadImage(userId, "consent");
            String sel64 = downloadImage(userId, "selfie");

            log.info("📊 Document sizes - Passport: {}, Address: {}, Consent: {}, Selfie: {}",
                    doc64.length(), addr64.length(), con64.length(), sel64.length());

            // Validate that we have all required documents
            if (doc64.isEmpty()) {
                log.error("❌ No passport document found for user: {}", userId);
                throw new UnauthorizedException("Passport document not found", "document");
            }
            if (addr64.isEmpty()) {
                log.error("❌ No address document found for user: {}", userId);
                throw new UnauthorizedException("Address document not found", "address");
            }
            if (con64.isEmpty()) {
                log.error("❌ No consent document found for user: {}", userId);
                throw new UnauthorizedException("Consent document not found", "consent");
            }
            if (sel64.isEmpty()) {
                log.error("❌ No selfie found for user: {}", userId);
                throw new UnauthorizedException("Selfie not found", "selfie");
            }

            log.info("✅ All documents validated for user: {}", userId);

            // Build ShuftiRequest
            log.info("🔨 Building ShuftiRequest for user: {}", userId);
            ShuftiRequest request;
            try {
                request = new ShuftiRequest(reference, country, doc64, addr64, fullAddr, con64, sel64, manualReview,
                        names);
                request.setCallback_url(CALLBACK_URL);
                request.setEmail("info@nyyu.io");
                request.setVerification_mode("image_only");
                request.setShow_results("1");
                request.setManual_review("1");
                log.info("✅ ShuftiRequest built successfully for user: {}", userId);
            } catch (Exception e) {
                log.error("❌ Failed to build ShuftiRequest for user: {}", userId, e);
                throw new RuntimeException("Failed to build verification request", e);
            }

            // Mark as pending before sending request
            log.info("⏳ Setting pending status for user: {}", userId);
            shuftiDao.updatePendingStatus(userId, true);

            // Send request synchronously
            log.info("🚀 Sending verification request to Shufti Pro for user: {}", userId);
            log.info("🔗 Request URL: {}", BASE_URL);
            log.info("🆔 Client ID: {}", CLIENT_ID.substring(0, Math.min(CLIENT_ID.length(), 4)) + "***");

            String requestJson;
            try {
                requestJson = objectMapper.writeValueAsString(request);
                log.info("📤 Request JSON length: {} characters", requestJson.length());
                // Log first 500 chars of request (be careful not to log sensitive data in
                // production)
                log.debug("📤 Request JSON preview: {}",
                        requestJson.substring(0, Math.min(requestJson.length(), 500)) + "...");
            } catch (Exception e) {
                log.error("❌ Failed to serialize request for user: {}", userId, e);
                shuftiDao.updatePendingStatus(userId, false);
                throw new RuntimeException("Failed to serialize verification request", e);
            }

            Response response;
            try {
                @SuppressWarnings("deprecation")
                Response tempResponse = sendPost("",
                        RequestBody.create(
                                MediaType.parse("application/json; charset=utf-8"),
                                requestJson));
                response = tempResponse;
            } catch (Exception e) {
                log.error("❌ HTTP request failed for user: {}", userId, e);
                shuftiDao.updatePendingStatus(userId, false);
                throw new RuntimeException("HTTP request to Shufti Pro failed", e);
            }

            String responseString;
            try {
                responseString = response.body().string();
            } catch (Exception e) {
                log.error("❌ Failed to read response body for user: {}", userId, e);
                shuftiDao.updatePendingStatus(userId, false);
                throw new RuntimeException("Failed to read Shufti Pro response", e);
            }

            log.info("📥 Shufti Pro response for user {}: Status={}", userId, response.code());
            log.info("📥 Response body length: {} characters", responseString.length());
            log.info("📥 Response body: {}", responseString);
            if (response.code() == 200 || response.code() == 201) {
                try {
                    // Use Gson instead of Jackson to avoid JsonNode instantiation error
                    Gson gson = createGson();

                    // First check if the response indicates an error
                    JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

                    // Check if response contains an error field
                    if (jsonResponse.has("error")) {
                        String errorMsg = "Shufti Pro API Error";
                        JsonElement errorElement = jsonResponse.get("error");
                        if (errorElement.isJsonObject()) {
                            JsonObject errorObj = errorElement.getAsJsonObject();
                            if (errorObj.has("message")) {
                                errorMsg = errorObj.get("message").getAsString();
                            }
                        } else if (errorElement.isJsonPrimitive()) {
                            errorMsg = errorElement.getAsString();
                        }

                        log.error("❌ Shufti Pro API error for user: {}, error: {}", userId, errorMsg);
                        shuftiDao.updatePendingStatus(userId, false);
                        throw new RuntimeException("Shufti Pro API Error: " + errorMsg);
                    }

                    // Try to parse as ShuftiResponse if no error
                    ShuftiResponse shuftiResponse = gson.fromJson(responseString, ShuftiResponse.class);

                    if (shuftiResponse != null) {
                        log.info("✅ Verification request successful for user: {}, event: {}", userId,
                                shuftiResponse.getEvent());
                        log.info("🔍 Response reference: {}", shuftiResponse.getReference());
                        log.info("🔍 Response message: {}", shuftiResponse.getMessage());

                        // Return the original reference that was sent, not from response
                        // because Shufti Pro echoes back the reference we sent
                        shuftiDao.updatePendingStatus(userId, false);
                        return reference;
                    } else {
                        log.error("❌ Null response from Shufti Pro for user: {}", userId);
                        shuftiDao.updatePendingStatus(userId, false);
                        throw new RuntimeException("Null response from verification service");
                    }
                } catch (JsonSyntaxException e) {
                    log.error("❌ Failed to parse Shufti Pro response for user: {}, JSON error: {}", userId,
                            e.getMessage());
                    log.error("❌ Raw response that failed to parse: {}", responseString);
                    shuftiDao.updatePendingStatus(userId, false);
                    throw new RuntimeException("Invalid JSON response from verification service", e);
                } catch (Exception e) {
                    log.error("❌ Unexpected error processing response for user: {}", userId, e);
                    shuftiDao.updatePendingStatus(userId, false);
                    throw new RuntimeException("Failed to process verification service response", e);
                }
            } else {
                log.error("❌ Failed verification request for user: {}, status: {}, response: {}", userId,
                        response.code(), responseString);
                shuftiDao.updatePendingStatus(userId, false);
                throw new RuntimeException("Verification service returned error: " + response.code());
            }

        } catch (UnauthorizedException e) {
            log.error("❌ Authorization error for user: {}", userId);
            throw e; // Re-throw UnauthorizedException as-is
        } catch (Exception e) {
            log.error("❌ Unexpected error in sendVerifyRequest for user: {}", userId, e);
            throw new RuntimeException("Verification request failed: " + e.getMessage(), e);
        }
    }

    public Integer kycStatusRequestAsync(String reference) {
        ShuftiStatusRequest request = new ShuftiStatusRequest(reference);
        try {
            @SuppressWarnings("deprecation")
            Response response = sendPost("status",
                    RequestBody.create(
                            MediaType.parse("application/json; charset=utf-8"),
                            objectMapper.writeValueAsString(request)));

            String responseString = response.body().string();

            // Check if response is an error first
            if (response.code() != 200 && response.code() != 201) {
                log.warn("Non-success response code: {}", response.code());
                log.warn("Status check failed with code {}: {}", response.code(), responseString);
                return 0; // Treat errors as "not verified"
            }

            // Use Gson instead of Jackson to avoid JsonNode instantiation error
            Gson gson = createGson();

            // Try to parse as JSON object first to check for errors
            JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

            if (jsonResponse.has("error")) {
                log.warn("Status check returned error: {}", jsonResponse.get("error"));
                return 0; // Treat errors as "not verified"
            }

            // Parse as ShuftiResponse
            ShuftiResponse shuftiResponse = gson.fromJson(responseString, ShuftiResponse.class);
            if (shuftiResponse.getEvent() != null && shuftiResponse.getEvent().equals("verification.accepted")) {
                return 1;
            }
            return 0;

        } catch (JsonSyntaxException e) {
            log.error("Failed to parse status response", e);
            return 0; // Treat parsing errors as "not verified"
        } catch (Exception e) {
            log.error("Unexpected error during status check", e);
            return 0; // Treat any errors as "not verified"
        }
    }

    public boolean kycStatusCkeck(int userId) {
        ShuftiReference _reference = shuftiDao.selectById(userId);

        if (_reference == null) {
            return false;
        }

        // Check if reference is valid before making API call
        String reference = _reference.getReference();
        if (reference == null || reference.trim().isEmpty()) {
            log.debug("No valid reference found for user: {}", userId);
            return false;
        }

        ShuftiStatusRequest request = new ShuftiStatusRequest(reference);
        try {
            @SuppressWarnings("deprecation")
            Response response = sendPost("status",
                    RequestBody.create(
                            MediaType.parse("application/json; charset=utf-8"),
                            objectMapper.writeValueAsString(request)));

            String responseString = response.body().string();

            // Check if response is an error first
            if (response.code() != 200 && response.code() != 201) {
                log.debug("Status check failed with code {} for user {}: {}", response.code(), userId, responseString);
                return false;
            }

            try {
                // Use Gson instead of Jackson to avoid JsonNode instantiation error
                Gson gson = createGson();

                // Try to parse as JSON object first to check for errors
                JsonObject jsonResponse = gson.fromJson(responseString, JsonObject.class);

                if (jsonResponse.has("error")) {
                    log.debug("Status check returned error for user {}: {}", userId, jsonResponse.get("error"));
                    return false;
                }

                // Parse as ShuftiResponse - but only use the event field
                if (jsonResponse.has("event")) {
                    String event = jsonResponse.get("event").getAsString();
                    if ("verification.accepted".equals(event)) {
                        return true;
                    }
                }

                return false;

            } catch (JsonSyntaxException e) {
                log.debug("Failed to parse status response for user {}: {}", userId, e.getMessage());
                return false;
            }

        } catch (IOException e) {
            log.debug("IOException during status check for user {}: {}", userId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.debug("Unexpected error during status check for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    public ShuftiResponse checkShuftiStatus(String reference) {
        ShuftiStatusRequest request = new ShuftiStatusRequest(reference);
        try {
            @SuppressWarnings("deprecation")
            Response response = sendPost("status",
                    RequestBody.create(
                            MediaType.parse("application/json; charset=utf-8"),
                            objectMapper.writeValueAsString(request)));

            String responseString = response.body().string();

            // Use Gson instead of Jackson
            Gson gson = createGson();
            ShuftiResponse shuftiResponse = gson.fromJson(responseString, ShuftiResponse.class);
            return shuftiResponse;
        } catch (IOException e) {
            log.error("IOException in checkShuftiStatus", e);
            return null;
        } catch (Exception e) {
            log.error("Unexpected error in checkShuftiStatus", e);
            return null;
        }
    }

    // private routines
    private String generateToken() {
        String combination = CLIENT_ID + ":" + SECRET_KEY;
        return Base64.getEncoder().encodeToString(combination.getBytes());
    }

    // Removed unused sendShuftiRequest method to eliminate warning

    private Response sendPost(String url, RequestBody requestBody) throws IOException {
        String token = generateToken();
        Request request = new Request.Builder()
                .url(BASE_URL + url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Authorization", "Basic " + token)
                .build();

        OkHttpClient client = new OkHttpClient();
        return client.newCall(request).execute();
    }

    public String downloadImage(int userId, String name) {
        try {
            log.info("📥 Attempting to download blob: {}-{} for user: {}", userId, name, userId);
            String blobName = userId + "-" + name;
            BlobClient blobClient = blobServiceClient.getBlobContainerClient(containerName)
                    .getBlobClient(blobName);

            if (!blobClient.exists()) {
                log.warn("⚠️ Blob {}-{} does not exist for user: {}", userId, name, userId);
                return "";
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            byte[] imageBytes = outputStream.toByteArray();
            outputStream.close();

            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            log.info("✅ Successfully downloaded {}-{} from Azure Blob Storage, size: {} bytes", userId, name,
                    imageBytes.length);
            return base64Image;
        } catch (Exception e) {
            log.error("❌ Failed to download {}-{} from Azure Blob Storage", userId, name, e);
            return "";
        }
    }

    public String createShuftiReference(int userId, String verificationType) {
        String reference = UUID.randomUUID().toString();
        ShuftiReference shuftiReference = new ShuftiReference();
        shuftiReference.setUserId(userId);
        shuftiReference.setReference(reference);
        shuftiReference.setVerificationType(verificationType);
        shuftiReference.setPending(false);

        // Save to database
        int result = shuftiDao.insert(shuftiReference);
        if (result > 0) {
            log.info("✅ Created new Shufti reference {} for user: {}", reference, userId);
            return reference;
        } else {
            log.error("❌ Failed to create Shufti reference for user: {}", userId);
            throw new RuntimeException("Failed to create verification reference");
        }
    }

    public String updateShuftiReference(int userId, String newReference) {
        ShuftiReference existing = shuftiDao.selectById(userId);
        if (existing != null) {
            existing.setReference(newReference);
            existing.setPending(false);
            int result = shuftiDao.update(existing);
            if (result > 0) {
                log.info("✅ Updated Shufti reference to {} for user: {}", newReference, userId);
                return newReference;
            } else {
                log.error("❌ Failed to update Shufti reference for user: {}", userId);
                throw new RuntimeException("Failed to update verification reference");
            }
        } else {
            log.warn("⚠️ No existing reference found for user: {}, creating new one", userId);
            return createShuftiReference(userId, "KYC");
        }
    }

    public ShuftiReference getShuftiReference(int userId) {
        return shuftiDao.selectById(userId);
    }

    public int insertOrUpdateReference(int userId, String reference) {
        try {
            ShuftiReference existing = shuftiDao.selectById(userId);
            if (existing != null) {
                // Update existing reference
                log.info("🔄 Updating existing Shufti reference for user: {} with new reference: {}", userId,
                        reference);
                int result = shuftiDao.updateReference(userId, reference);
                if (result > 0) {
                    log.info("✅ Updated Shufti reference for user: {}", userId);
                } else {
                    log.error("❌ Failed to update Shufti reference for user: {}", userId);
                }
                return result;
            } else {
                // Insert new reference
                log.info("➕ Creating new Shufti reference for user: {} with reference: {}", userId, reference);
                ShuftiReference newReference = new ShuftiReference();
                newReference.setUserId(userId);
                newReference.setReference(reference);
                newReference.setVerificationType("KYC");
                newReference.setDocStatus(false);
                newReference.setAddrStatus(false);
                newReference.setConStatus(false);
                newReference.setSelfieStatus(false);
                newReference.setPending(false);

                int result = shuftiDao.insert(newReference);
                if (result > 0) {
                    log.info("✅ Created new Shufti reference for user: {}", userId);
                } else {
                    log.error("❌ Failed to create Shufti reference for user: {}", userId);
                }
                return result;
            }
        } catch (Exception e) {
            log.error("❌ Error in insertOrUpdateReference for user: {}, reference: {}", userId, reference, e);
            return 0;
        }
    }
}