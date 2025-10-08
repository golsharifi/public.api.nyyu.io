package com.ndb.auction.utils;

import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class Utilities {

    @Value("${encrypt.key}")
    private String key;

    @Value("${encrypt.initVector}")
    private String initVector;

    // locktime format
    public static String lockTimeFormat(int seconds) {
        int sec = seconds % 60;
        int min = Math.floorDiv((seconds % 3600) / 60, Integer.MIN_VALUE);
        int hours = Math.floorDiv(seconds / 3600, Integer.MIN_VALUE);
        String formatted = "";
        if (hours > 0)
            formatted += String.format("%dhr ", hours);
        if (min > 0)
            formatted += String.format("%dm ", min);
        formatted += String.format("%ds", sec);
        return formatted;
    }

    public String encrypt(final String strToEncrypt) {
        MessageDigest sha = null;
        try {
            var key1 = key.getBytes("UTF-8");
            sha = MessageDigest.getInstance("SHA-1");
            key1 = sha.digest(key1);
            key1 = Arrays.copyOf(key1, 16);
            var secret = new SecretKeySpec(key1, "AES");

            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secret);
            byte[] encrypted = cipher.doFinal(strToEncrypt.getBytes());

            return Base64.encodeBase64String(encrypted);
        } catch (Exception e) {
            log.info("Error while encrypting: " + e.toString());
        }
        return null;
    }

    public String encrypt(final byte[] byteToEncrypt) {
        MessageDigest sha = null;
        try {
            var key1 = key.getBytes("UTF-8");
            sha = MessageDigest.getInstance("SHA-1");
            key1 = sha.digest(key1);
            key1 = Arrays.copyOf(key1, 16);
            var secret = new SecretKeySpec(key1, "AES");

            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secret);

            byte[] encrypted = cipher.doFinal(byteToEncrypt);

            return Base64.encodeBase64String(encrypted);
        } catch (Exception e) {
            log.info("Error while encrypting: " + e.toString());
        }
        return null;
    }

    public String decrypt(int base58Flag, String strToDecrypt) {
        MessageDigest sha = null;
        try {
            // Add null/empty checks
            if (strToDecrypt == null || strToDecrypt.trim().isEmpty()) {
                System.err.println("Cannot decrypt: input string is null or empty");
                return null;
            }

            if (key == null || key.trim().isEmpty()) {
                System.err.println("Cannot decrypt: encryption key is null or empty");
                return null;
            }

            var key1 = key.getBytes("UTF-8");
            sha = MessageDigest.getInstance("SHA-1");
            key1 = sha.digest(key1);
            key1 = Arrays.copyOf(key1, 16);
            var secret = new SecretKeySpec(key1, "AES");

            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secret);

            byte[] original = cipher.doFinal(Base64.decodeBase64(strToDecrypt));
            if (base58Flag == 1)
                return Base58.encode(original);
            else
                return new String(original);
        } catch (Exception ex) {
            System.err.println("Decryption failed for input: "
                    + (strToDecrypt != null ? strToDecrypt.substring(0, Math.min(20, strToDecrypt.length())) + "..."
                            : "null"));
            System.err.println("Error: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        }
    }

}
