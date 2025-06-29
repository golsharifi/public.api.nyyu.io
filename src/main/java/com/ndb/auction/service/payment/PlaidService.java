package com.ndb.auction.service.payment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import com.ndb.auction.service.BaseService;
import com.plaid.client.ApiClient;
import com.plaid.client.model.CountryCode;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateRequestPaymentInitiation;
import com.plaid.client.model.LinkTokenCreateRequestUser;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.model.PaymentAmount;
import com.plaid.client.model.PaymentAmountCurrency;
import com.plaid.client.model.PaymentInitiationAddress;
import com.plaid.client.model.PaymentInitiationPaymentCreateRequest;
import com.plaid.client.model.PaymentInitiationPaymentCreateResponse;
import com.plaid.client.model.PaymentInitiationRecipientCreateRequest;
import com.plaid.client.model.PaymentInitiationRecipientCreateResponse;
import com.plaid.client.model.Products;
import com.plaid.client.model.RecipientBACSNullable;
import com.plaid.client.request.PlaidApi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import retrofit2.Response;
import okhttp3.ResponseBody;

@Service
public class PlaidService extends BaseService {

    @Value("${plaid.client.id}")
    private String CLIENT_ID;

    @Value("${plaid.secret.key}")
    private String SECRET;

    private static PlaidApi plaidClient;

    @PostConstruct
    public void init() {
        // Create your Plaid client
        Map<String, String> apiKeys = new HashMap<>();
        apiKeys.put("clientId", CLIENT_ID);
        apiKeys.put("secret", SECRET);
        ApiClient apiClient = new ApiClient(apiKeys);

        apiClient.setPlaidAdapter(ApiClient.Sandbox);
        plaidClient = apiClient.createService(PlaidApi.class);
    }

    // Helper method to safely extract error message
    private String getErrorMessage(Response<?> response, String defaultMessage) {
        StringBuilder errorMessage = new StringBuilder(defaultMessage);

        try {
            ResponseBody errorBody = response.errorBody();
            if (errorBody != null) {
                String errorString = errorBody.string();
                if (errorString != null && !errorString.trim().isEmpty()) {
                    errorMessage.append(". Error: ").append(errorString);
                }
            }
        } catch (IOException e) {
            // Log the error but don't fail here
            System.err.println("Could not read error body: " + e.getMessage());
        }

        return errorMessage.toString();
    }

    // Create a recipient with complete null safety
    private String createRecipient() throws IOException {
        PaymentInitiationAddress address = new PaymentInitiationAddress()
                .street(Arrays.asList("56 Shoreditch High Street"))
                .city("London")
                .postalCode("E1 6JJ")
                .country("GB");

        RecipientBACSNullable basc = new RecipientBACSNullable()
                .account("22063784")
                .sortCode("23-14-70");

        PaymentInitiationRecipientCreateRequest request = new PaymentInitiationRecipientCreateRequest()
                .name("Voltamond")
                .bacs(basc)
                .address(address);

        Response<PaymentInitiationRecipientCreateResponse> response = plaidClient
                .paymentInitiationRecipientCreate(request)
                .execute();

        // Complete null safety check
        PaymentInitiationRecipientCreateResponse responseBody = response.body();
        if (responseBody == null) {
            String errorMessage = getErrorMessage(response, "Failed to create recipient: response body is null");
            throw new IOException(errorMessage);
        }

        String recipientId = responseBody.getRecipientId();
        if (recipientId == null || recipientId.trim().isEmpty()) {
            throw new IOException("Failed to create recipient: recipient ID is null or empty");
        }

        return recipientId;
    }

    // Create a payment with complete null safety
    private String createPayment(String recipientId) throws IOException {
        if (recipientId == null || recipientId.trim().isEmpty()) {
            throw new IllegalArgumentException("Recipient ID cannot be null or empty");
        }

        PaymentAmount amount = new PaymentAmount()
                .currency(PaymentAmountCurrency.GBP)
                .value(999.99);

        PaymentInitiationPaymentCreateRequest request = new PaymentInitiationPaymentCreateRequest()
                .recipientId(recipientId)
                .reference("reference")
                .amount(amount);

        Response<PaymentInitiationPaymentCreateResponse> response = plaidClient
                .paymentInitiationPaymentCreate(request)
                .execute();

        // Complete null safety check
        PaymentInitiationPaymentCreateResponse responseBody = response.body();
        if (responseBody == null) {
            String errorMessage = getErrorMessage(response, "Failed to create payment: response body is null");
            throw new IOException(errorMessage);
        }

        String paymentId = responseBody.getPaymentId();
        if (paymentId == null || paymentId.trim().isEmpty()) {
            throw new IOException("Failed to create payment: payment ID is null or empty");
        }

        return paymentId;
    }

    // Create link token with complete null safety
    public LinkTokenCreateResponse createLinkToken(int userId) throws IOException {
        String recipientId = createRecipient();
        String paymentId = createPayment(recipientId);

        LinkTokenCreateRequestUser user = new LinkTokenCreateRequestUser()
                .clientUserId(String.valueOf(userId));

        LinkTokenCreateRequestPaymentInitiation paymentInitiation = new LinkTokenCreateRequestPaymentInitiation()
                .paymentId(paymentId);

        List<Products> productList = new ArrayList<>();
        productList.add(Products.PAYMENT_INITIATION);

        List<CountryCode> codeList = new ArrayList<>();
        codeList.add(CountryCode.GB);

        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(user)
                .clientName("Plaid Test App")
                .products(productList)
                .countryCodes(codeList)
                .language("en")
                .redirectUri("redirectUri")
                .webhook("https://sample.webhook.com")
                .paymentInitiation(paymentInitiation);

        Response<LinkTokenCreateResponse> response = plaidClient
                .linkTokenCreate(request)
                .execute();

        // Complete null safety check
        LinkTokenCreateResponse responseBody = response.body();
        if (responseBody == null) {
            String errorMessage = getErrorMessage(response, "Failed to create link token: response body is null");
            throw new IOException(errorMessage);
        }

        return responseBody;
    }

    public ItemPublicTokenExchangeResponse getExchangeToken(String publicToken) throws IOException {
        if (publicToken == null || publicToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Public token cannot be null or empty");
        }

        ItemPublicTokenExchangeRequest request = new ItemPublicTokenExchangeRequest()
                .publicToken(publicToken);

        Response<ItemPublicTokenExchangeResponse> response = plaidClient
                .itemPublicTokenExchange(request)
                .execute();

        // Complete null safety check
        ItemPublicTokenExchangeResponse responseBody = response.body();
        if (responseBody == null) {
            String errorMessage = getErrorMessage(response, "Failed to exchange token: response body is null");
            throw new IOException(errorMessage);
        }

        return responseBody;
    }
}