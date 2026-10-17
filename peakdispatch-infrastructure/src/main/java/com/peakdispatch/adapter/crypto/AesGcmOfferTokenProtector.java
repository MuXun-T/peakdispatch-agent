package com.peakdispatch.adapter.crypto;

import com.peakdispatch.application.dispatch.OfferTokenProtector;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AesGcmOfferTokenProtector implements OfferTokenProtector {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmOfferTokenProtector(@Value("${peakdispatch.security.offer-encryption-key}") String configuredKey) {
        try {
            this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(configuredKey.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("AES-GCM is unavailable", impossible);
        }
    }

    @Override
    public String protect(String token) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("offer token encryption failed", exception);
        }
    }

    @Override
    public String reveal(String ciphertext) {
        byte[] encoded = Base64.getDecoder().decode(ciphertext);
        if (encoded.length <= NONCE_BYTES) {
            throw new IllegalArgumentException("invalid offer token ciphertext");
        }
        byte[] nonce = java.util.Arrays.copyOf(encoded, NONCE_BYTES);
        byte[] encrypted = java.util.Arrays.copyOfRange(encoded, NONCE_BYTES, encoded.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("offer token decryption failed", exception);
        }
    }
}
