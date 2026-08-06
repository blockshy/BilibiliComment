package com.hy.bilicomment.infrastructure.bilibili;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WbiSignerTests {

    private static final String IMAGE_KEY = "7cd084941338484aae1ad9425b84077c";
    private static final String SUB_KEY = "4932caff0ff746eab6f01bf08b70ac45";

    @Test
    void signsThePublishedWbiRegressionVector() {
        WbiSigner signer = new WbiSigner(Clock.fixed(Instant.ofEpochSecond(1_702_204_169L), ZoneOffset.UTC));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("foo", "114");
        parameters.put("bar", "514");
        parameters.put("zab", "1919810");

        WbiSigner.SignedQuery signed = signer.sign(parameters, IMAGE_KEY, SUB_KEY);

        assertThat(signed.query()).isEqualTo(
                "bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4");
    }

    @Test
    void removesForbiddenCharactersAndReplacesExistingSignature() {
        WbiSigner signer = new WbiSigner(Clock.fixed(Instant.ofEpochSecond(100), ZoneOffset.UTC));
        WbiSigner.SignedQuery signed = signer.sign(
                Map.of("message", "a!b'c(d)e*f", "w_rid", "stale"), IMAGE_KEY, SUB_KEY);

        assertThat(signed.query()).contains("message=abcdef").doesNotContain("stale");
    }
}
