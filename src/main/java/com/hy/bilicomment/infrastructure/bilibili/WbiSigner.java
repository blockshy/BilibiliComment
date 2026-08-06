package com.hy.bilicomment.infrastructure.bilibili;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class WbiSigner {

    private static final int[] MIXIN_KEY_ORDER = {
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };
    private static final Pattern FORBIDDEN_VALUE_CHARACTERS = Pattern.compile("[!'()*]");

    private final Clock clock;

    public WbiSigner(Clock clock) {
        this.clock = clock;
    }

    public SignedQuery sign(Map<String, String> input, String imageKey, String subKey) {
        long timestamp = clock.instant().getEpochSecond();
        return sign(input, imageKey, subKey, timestamp);
    }

    SignedQuery sign(Map<String, String> input, String imageKey, String subKey, long timestamp) {
        if (imageKey == null || imageKey.isBlank() || subKey == null || subKey.isBlank()) {
            throw new IllegalArgumentException("WBI image and sub keys are required");
        }

        Map<String, String> parameters = new LinkedHashMap<>(input);
        parameters.remove("w_rid");
        parameters.put("wts", Long.toString(timestamp));

        List<Map.Entry<String, String>> sorted = new ArrayList<>(parameters.entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted) {
            if (!canonical.isEmpty()) {
                canonical.append('&');
            }
            canonical.append(encode(entry.getKey()))
                    .append('=')
                    .append(encode(sanitize(entry.getValue())));
        }

        String signature = md5(canonical + mixinKey(imageKey, subKey));
        return new SignedQuery(canonical + "&w_rid=" + signature, timestamp, signature);
    }

    String mixinKey(String imageKey, String subKey) {
        String source = imageKey + subKey;
        if (source.length() < MIXIN_KEY_ORDER.length) {
            throw new IllegalArgumentException("WBI key material is incomplete");
        }
        StringBuilder mixed = new StringBuilder(MIXIN_KEY_ORDER.length);
        for (int index : MIXIN_KEY_ORDER) {
            mixed.append(source.charAt(index));
        }
        return mixed.substring(0, 32);
    }

    private String sanitize(String value) {
        return FORBIDDEN_VALUE_CHARACTERS.matcher(value == null ? "" : value).replaceAll("");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }

    public record SignedQuery(String query, long timestamp, String signature) {}
}
