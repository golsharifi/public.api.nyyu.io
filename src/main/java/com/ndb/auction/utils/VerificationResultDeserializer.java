package com.ndb.auction.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ndb.auction.models.Shufti.Response.VerificationResult;

import java.io.IOException;

public class VerificationResultDeserializer extends JsonDeserializer<VerificationResult> {

    @Override
    public VerificationResult deserialize(JsonParser jp, DeserializationContext ctxt)
            throws IOException, JsonProcessingException {

        ObjectMapper mapper = (ObjectMapper) jp.getCodec();
        JsonNode node = mapper.readTree(jp);

        // Check if the node is an array
        if (node.isArray()) {
            // If it's an array, return an empty VerificationResult or handle as needed
            // This happens when the verification is still pending
            return new VerificationResult();
        }

        // Otherwise, parse as object
        VerificationResult result = new VerificationResult();

        // Parse document verification
        JsonNode documentNode = node.get("document");
        if (documentNode != null && !documentNode.isNull()) {
            VerificationResult.DocumentVerification doc = new VerificationResult.DocumentVerification();
            doc.setDocument(getIntValue(documentNode, "document"));
            doc.setName(getIntValue(documentNode, "name"));
            doc.setDob(getIntValue(documentNode, "dob"));
            doc.setExpiry_date(getIntValue(documentNode, "expiry_date"));
            doc.setIssue_date(getIntValue(documentNode, "issue_date"));
            result.setDocument(doc);
        }

        // Parse address verification
        JsonNode addressNode = node.get("address");
        if (addressNode != null && !addressNode.isNull()) {
            VerificationResult.AddressVerification addr = new VerificationResult.AddressVerification();
            addr.setAddress_document(getIntValue(addressNode, "address_document"));
            addr.setFull_address(getIntValue(addressNode, "full_address"));
            result.setAddress(addr);
        }

        // Parse consent verification
        JsonNode consentNode = node.get("consent");
        if (consentNode != null && !consentNode.isNull()) {
            VerificationResult.ConsentVerification consent = new VerificationResult.ConsentVerification();
            consent.setConsent(getIntValue(consentNode, "consent"));
            result.setConsent(consent);
        }

        // Parse face verification
        JsonNode faceNode = node.get("face");
        if (faceNode != null && !faceNode.isNull()) {
            result.setFace(faceNode.asInt());
        }

        // Parse additional fields if present
        JsonNode statusNode = node.get("status");
        if (statusNode != null && !statusNode.isNull()) {
            result.setStatus(statusNode.asText());
        }

        JsonNode messageNode = node.get("message");
        if (messageNode != null && !messageNode.isNull()) {
            result.setMessage(messageNode.asText());
        }

        return result;
    }

    private Integer getIntValue(JsonNode parentNode, String fieldName) {
        JsonNode fieldNode = parentNode.get(fieldName);
        if (fieldNode != null && !fieldNode.isNull()) {
            return fieldNode.asInt();
        }
        return null;
    }
}