package com.demo.jpa.crypto;

import jakarta.persistence.AttributeConverter;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * A text column encrypted at rest, AES-256-GCM, with a key the deployment must supply. The stored value is
 * {@code v1:} plus base64 of a fresh 12-byte nonce and the ciphertext with its tag, so two equal values never
 * look alike on disk and a tampered one fails to decrypt rather than coming back wrong.
 *
 * <p>A JPA converter, so the entity reads and writes plain text and nothing else in the service knows. A
 * value without the prefix is one written before encryption existed; it is returned as it is, and
 * {@link EncryptedColumnMigration} rewrites every such row so none stays plain for longer than one boot.
 *
 * <p>Subclass it per column with the key's property, and keep the subclass a Spring bean and a {@code @Converter}:
 * <pre>
 * &#64;Component &#64;Converter
 * public class AuthHeaderCrypto extends EncryptedText {
 *     public AuthHeaderCrypto(&#64;Value("${agents.auth-header-key:}") String key) { super(key); }
 * }
 * </pre>
 */
// ponytail: one key, no rotation. The v1 prefix is what a second key version would key on.
public class EncryptedText implements AttributeConverter<String, String> {

    public static final String PREFIX = "v1:";

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;

    private final SecureRandom random = new SecureRandom();

    /** Any string will do as the secret; its SHA-256 is the 32-byte AES key. Blank refuses to start rather than storing plain text. */
    public EncryptedText(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("no encryption key is set: the column would be stored as plain text");
        }
        try {
            this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.strip().getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        return plain == null ? null : encrypt(plain);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return stored == null || !stored.startsWith(PREFIX) ? stored : decrypt(stored);
    }

    public String encrypt(String plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(nonce, nonce.length + sealed.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt a value", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
            return new String(cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("could not decrypt a stored value: wrong key, or the row was altered", e);
        }
    }
}
