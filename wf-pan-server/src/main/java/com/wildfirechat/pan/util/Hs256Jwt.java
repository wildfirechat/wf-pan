package com.wildfirechat.pan.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * 最小的 HS256 JWT 签发/校验（ONLYOFFICE 只用 HS256，不值得为此引一个库）
 */
public final class Hs256Jwt {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private Hs256Jwt() {}

    public static String sign(Map<String, ?> payload, String secret) {
        try {
            String body = B64.encodeToString(MAPPER.writeValueAsBytes(payload));
            String signingInput = HEADER + "." + body;
            return signingInput + "." + B64.encodeToString(hmac(signingInput, secret));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    /**
     * 校验签名（只接受 HS256）与 exp，通过则返回 payload，否则返回 null
     */
    public static Map<String, Object> verify(String token, String secret) {
        if (token == null || secret == null || secret.isEmpty()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            Map<String, Object> header = MAPPER.readValue(B64D.decode(parts[0]), new TypeReference<>() {});
            if (!"HS256".equals(header.get("alg"))) {
                return null;
            }
            byte[] expected = hmac(parts[0] + "." + parts[1], secret);
            if (!MessageDigest.isEqual(expected, B64D.decode(parts[2]))) {
                return null;
            }
            Map<String, Object> payload = MAPPER.readValue(B64D.decode(parts[1]), new TypeReference<>() {});
            Object exp = payload.get("exp");
            if (exp instanceof Number n && n.longValue() * 1000 < System.currentTimeMillis()) {
                return null;
            }
            return payload;
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacB64(String data, String secret) {
        return B64.encodeToString(hmac(data, secret));
    }
}
