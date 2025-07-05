package com.ndb.auction.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.servlet.http.Part;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import reactor.core.publisher.Mono;
import lombok.extern.slf4j.Slf4j;

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

    @Autowired
    private BlobServiceClient blobServiceClient;

    private WebClient shuftiAPI;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    protected CloseableHttpClient client;

    public ShuftiService(WebClient.Builder webClientBuilder) {
        client = HttpClients.createDefault();
        this.shuftiAPI = webClientBuilder
                .baseUrl(BASE_URL)
                .build();
    }

    // Create new application
    public String createShuftiReference(int userId, String verifyType) {
        ShuftiReference sRef = shuftiDao.selectById(userId);
        if (sRef != null) {
            return sRef.getReference();
        }
        String reference = UUID.randomUUID().toString();
        sRef = new ShuftiReference(userId, reference);
        shuftiDao.insert(sRef);
        return reference;
    }

    public String updateShuftiReference(int userId, String reference) {
        shuftiDao.updateReference(userId, reference);
        return reference;
    }

    public ShuftiReference getShuftiReference(int userId) {
        return shuftiDao.selectById(userId);
    }

    // kyc verification
    public int kycRequest(int userId, ShuftiRequest request) throws JsonProcessingException, IOException {
        // add supported types
        List<String> docSupportedTypes = new ArrayList<>();
        docSupportedTypes.add("passport");
        request.getDocument().setSupported_types(docSupportedTypes);

        List<String> addrSupportedTypes = new ArrayList<>();
        addrSupportedTypes.add("id_card");
        request.getAddress().setSupported_types(addrSupportedTypes);

        List<String> consentTypes = new ArrayList<>();
        consentTypes.add("handwritten");
        request.getConsent().setSupported_types(consentTypes);
        request.getConsent().setText("I & NDB");

        request.setCallback_url(CALLBACK_URL);

        sendShuftiRequest(request)
                .subscribe(response -> System.out.println(response));
        return 1;
    }

    public int kycStatusRequestAsync(String reference)
            throws JsonProcessingException, IOException {
        ShuftiStatusRequest request = new ShuftiStatusRequest(reference);

        @SuppressWarnings("deprecation")
        Response _response = sendPost("status",
                RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        objectMapper.writeValueAsString(request)));

        String _responseString = _response.body().string();
        ShuftiResponse response = gson.fromJson(_responseString, ShuftiResponse.class);
        if (response.getEvent() != null && response.getEvent().equals("verification.accepted")) {
            return 1;
        }
        return 0;
    }

    public boolean kycStatusCkeck(int userId) {
        ShuftiReference _reference = shuftiDao.selectById(userId);

        if (_reference == null) {
            return false;
        }

        ShuftiStatusRequest request = new ShuftiStatusRequest(_reference.getReference());
        try {
            @SuppressWarnings("deprecation")
            Response _response = sendPost("status",
                    RequestBody.create(
                            MediaType.parse("application/json; charset=utf-8"),
                            objectMapper.writeValueAsString(request)));

            String _responseString = _response.body().string();
            ShuftiResponse response = gson.fromJson(_responseString, ShuftiResponse.class);
            if (response.getEvent().equals("verification.accepted")) {
                return true;
            }
            return false;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    public ShuftiResponse checkShuftiStatus(String reference) {
        ShuftiStatusRequest request = new ShuftiStatusRequest(reference);
        try {
            @SuppressWarnings("deprecation")
            Response _response = sendPost("status",
                    RequestBody.create(
                            MediaType.parse("application/json; charset=utf-8"),
                            objectMapper.writeValueAsString(request)));

            String _responseString = _response.body().string();
            ShuftiResponse response = gson.fromJson(_responseString, ShuftiResponse.class);
            return response;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    // private routines
    private String generateToken() {
        String combination = CLIENT_ID + ":" + SECRET_KEY;
        return Base64.getEncoder().encodeToString(combination.getBytes());
    }

    //// WebClient
    private Mono<String> sendShuftiRequest(ShuftiRequest request) {
        try {
            String token = generateToken();
            return shuftiAPI.post()
                    .uri(uriBuilder -> uriBuilder.path("").build())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Basic " + token)
                    .body(Mono.just(request), ShuftiRequest.class)
                    .retrieve()
                    .bodyToMono(String.class);
        } catch (Exception e) {
            log.error("Failed to generate token or send request", e);
            return Mono.error(e);
        }
    }

    private Response sendPost(String url, RequestBody requestBody) throws IOException {
        String token = generateToken();
        Request request = new Request.Builder()
                .url(BASE_URL + url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + token)
                .post(requestBody)
                .build();

        Response response = new OkHttpClient().newCall(request).execute();

        if (response.code() != 200 && response.code() != 201) {
            log.warn("Non-success response code: {}", response.code());
        }
        return response;
    }

    // ====================== Azure Blob Storage Upload Methods ===============
    public Boolean uploadDocument(int userId, Part document) {
        ShuftiReference refObj = shuftiDao.selectById(userId);
        if (refObj == null) {
            createShuftiReference(userId, "KYC");
        }
        String blobName = String.format("%d-passport", userId);
        return uploadToAzureBlob(document, blobName);
    }

    public Boolean uploadAddress(int userId, Part addr) {
        ShuftiReference refObj = shuftiDao.selectById(userId);
        if (refObj == null) {
            String msg = messageSource.getMessage("no_ref", null, Locale.ENGLISH);
            throw new UnauthorizedException(msg, "reference");
        }
        String blobName = String.format("%d-address", userId);
        return uploadToAzureBlob(addr, blobName);
    }

    public Boolean uploadConsent(int userId, Part consent) {
        ShuftiReference refObj = shuftiDao.selectById(userId);
        if (refObj == null) {
            String msg = messageSource.getMessage("no_ref", null, Locale.ENGLISH);
            throw new UnauthorizedException(msg, "reference");
        }
        String blobName = String.format("%d-consent", userId);
        return uploadToAzureBlob(consent, blobName);
    }

    public Boolean uploadSelfie(int userId, Part selfie) {
        ShuftiReference refObj = shuftiDao.selectById(userId);
        if (refObj == null) {
            String msg = messageSource.getMessage("no_ref", null, Locale.ENGLISH);
            throw new UnauthorizedException(msg, "reference");
        }
        String blobName = String.format("%d-selfie", userId);
        return uploadToAzureBlob(selfie, blobName);
    }

    // Helper method to upload to Azure Blob Storage
    private Boolean uploadToAzureBlob(Part file, String blobName) {
        try {
            BlobClient blobClient = blobServiceClient
                    .getBlobContainerClient(containerName)
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
                request = new ShuftiRequest(reference, country, doc64, addr64, fullAddr, con64, sel64, names);
                request.setCallback_url(CALLBACK_URL);
                request.setEmail("verification@nyyu.io");
                request.setVerification_mode("image_only");
                request.setShow_results("1");
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
                    ShuftiResponse shuftiResponse = gson.fromJson(responseString, ShuftiResponse.class);

                    if (shuftiResponse != null) {
                        log.info("✅ Verification request successful for user: {}, event: {}", userId,
                                shuftiResponse.getEvent());
                        log.info("🔍 Response reference: {}", shuftiResponse.getReference());
                        log.info("🔍 Response message: {}", shuftiResponse.getMessage());
                        return reference;
                    } else {
                        log.error("❌ Null response from Shufti Pro for user: {}", userId);
                        shuftiDao.updatePendingStatus(userId, false);
                        throw new RuntimeException("Null response from verification service");
                    }
                } catch (Exception e) {
                    log.error("❌ Failed to parse Shufti Pro response for user: {}", userId, e);
                    log.error("❌ Raw response that failed to parse: {}", responseString);
                    shuftiDao.updatePendingStatus(userId, false);
                    throw new RuntimeException("Failed to parse verification service response", e);
                }
            } else {
                log.error("❌ Failed verification request for user: {}, status: {}, response: {}", userId,
                        response.code(), responseString);
                shuftiDao.updatePendingStatus(userId, false);
                throw new RuntimeException(
                        "Verification service returned error: " + response.code() + " - " + responseString);
            }

        } catch (UnauthorizedException e) {
            log.error("❌ Authorization error for user: {}", userId, e);
            shuftiDao.updatePendingStatus(userId, false);
            throw e;
        } catch (Exception e) {
            log.error("❌ Unexpected error in verification for user: {}", userId, e);
            shuftiDao.updatePendingStatus(userId, false);
            throw new RuntimeException("Verification request failed: " + e.getMessage(), e);
        }
    }

    // Download image from Azure Blob Storage and convert to Base64
    // Download image from Azure Blob Storage and convert to Base64
    private String downloadImage(int userId, String type) {
        try {
            String blobName = String.format("%d-%s", userId, type);
            log.info("📥 Attempting to download blob: {} for user: {}", blobName, userId);

            BlobClient blobClient = blobServiceClient
                    .getBlobContainerClient(containerName)
                    .getBlobClient(blobName);

            if (!blobClient.exists()) {
                log.warn("⚠️ Blob {} does not exist for user: {}", blobName, userId);
                return "";
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream); // Fixed: use downloadStream instead of download

            byte[] data = outputStream.toByteArray();
            String imageString = Base64.getEncoder().withoutPadding().encodeToString(data);

            outputStream.close();
            log.info("✅ Successfully downloaded {} from Azure Blob Storage, size: {} bytes", blobName, data.length);
            return imageString;
        } catch (Exception e) {
            log.error("❌ Failed to download image for userId: {}, type: {}", userId, type, e);
            return "";
        }
    }

    // frontend version
    public int insertOrUpdateReference(int userId, String reference) {
        ShuftiReference ref = shuftiDao.selectById(userId);
        if (ref != null) {
            return shuftiDao.updateReference(userId, reference);
        }
        ref = new ShuftiReference(userId, reference);
        return shuftiDao.insert(ref);
    }
}