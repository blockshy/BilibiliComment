package com.hy.bilicomment.application.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.SourceType;
import org.junit.jupiter.api.Test;

class SourceNormalizerTests {

    private final SourceNormalizer normalizer = new SourceNormalizer();

    @Test
    void normalizesVideoIdAndAllowlistedUrl() {
        assertThat(normalizer.normalize(SourceType.VIDEO, "BV1om421g72j").id())
                .isEqualTo("BV1om421g72j");
        assertThat(normalizer.normalize(
                                SourceType.VIDEO,
                                "https://www.bilibili.com/video/BV1om421g72j/?spm_id_from=333")
                        .canonicalUrl())
                .isEqualTo("https://www.bilibili.com/video/BV1om421g72j");
    }

    @Test
    void normalizesDynamicAndCreatorInputs() {
        assertThat(normalizer.normalize(SourceType.DYNAMIC, "https://www.bilibili.com/opus/123456789").id())
                .isEqualTo("123456789");
        assertThat(normalizer.normalize(SourceType.DYNAMIC, "https://t.bilibili.com/123456789").id())
                .isEqualTo("123456789");
        assertThat(normalizer.normalize(SourceType.CREATOR, "https://space.bilibili.com/998877").id())
                .isEqualTo("998877");
    }

    @Test
    void rejectsNonBilibiliUrlsWithoutFetchingThem() {
        assertThatThrownBy(() -> normalizer.normalize(
                        SourceType.VIDEO,
                        "https://example.com/video/BV1om421g72j"))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).getCode())
                .isEqualTo("SOURCE_HOST_NOT_ALLOWED");
    }
}
