package com.hy.bilicomment.application.source;

import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.source.NormalizedSource;
import com.hy.bilicomment.domain.task.SourceType;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SourceNormalizer {

    private static final Pattern BVID = Pattern.compile("(?i)(BV[0-9A-Za-z]{10})");
    private static final Pattern DIGITS = Pattern.compile("^[1-9][0-9]{0,19}$");
    private static final Pattern DYNAMIC_PATH = Pattern.compile("/(?:opus|dynamic)/([1-9][0-9]{0,19})(?:/.*)?$");
    private static final Pattern T_DYNAMIC_PATH = Pattern.compile("/([1-9][0-9]{0,19})(?:/.*)?$");
    private static final Pattern SPACE_PATH = Pattern.compile("/([1-9][0-9]{0,19})(?:/.*)?$");
    private static final Set<String> VIDEO_HOSTS = Set.of("www.bilibili.com", "m.bilibili.com");
    private static final Set<String> DYNAMIC_HOSTS = Set.of("www.bilibili.com", "m.bilibili.com", "t.bilibili.com");
    private static final Set<String> CREATOR_HOSTS = Set.of("space.bilibili.com");

    public NormalizedSource normalize(SourceType type, String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new DomainException("SOURCE_REQUIRED", "来源不能为空");
        }
        String input = rawInput.trim();
        return switch (type) {
            case VIDEO -> normalizeVideo(input);
            case DYNAMIC -> normalizeDynamic(input);
            case CREATOR -> normalizeCreator(input);
        };
    }

    private NormalizedSource normalizeVideo(String input) {
        URI uri = parseUriIfPresent(input);
        if (uri != null) {
            requireHost(uri, VIDEO_HOSTS);
        }
        Matcher matcher = BVID.matcher(input);
        if (!matcher.find()) {
            throw new DomainException("INVALID_VIDEO_SOURCE", "请输入 BV 号或包含 BV 号的 Bilibili 视频链接");
        }
        String matched = matcher.group(1);
        String bvid = "BV" + matched.substring(2);
        return new NormalizedSource(SourceType.VIDEO, bvid, "https://www.bilibili.com/video/" + bvid);
    }

    private NormalizedSource normalizeDynamic(String input) {
        if (DIGITS.matcher(input).matches()) {
            return dynamic(input);
        }
        URI uri = requireUri(input);
        requireHost(uri, DYNAMIC_HOSTS);
        Pattern pattern = "t.bilibili.com".equals(normalizedHost(uri)) ? T_DYNAMIC_PATH : DYNAMIC_PATH;
        Matcher matcher = pattern.matcher(uri.getPath());
        if (!matcher.matches()) {
            throw new DomainException("INVALID_DYNAMIC_SOURCE", "请输入动态 ID 或 Bilibili 动态链接");
        }
        return dynamic(matcher.group(1));
    }

    private NormalizedSource dynamic(String id) {
        return new NormalizedSource(SourceType.DYNAMIC, id, "https://www.bilibili.com/opus/" + id);
    }

    private NormalizedSource normalizeCreator(String input) {
        if (DIGITS.matcher(input).matches()) {
            return creator(input);
        }
        URI uri = requireUri(input);
        requireHost(uri, CREATOR_HOSTS);
        Matcher matcher = SPACE_PATH.matcher(uri.getPath());
        if (!matcher.matches()) {
            throw new DomainException("INVALID_CREATOR_SOURCE", "请输入 UID 或 Bilibili 空间链接");
        }
        return creator(matcher.group(1));
    }

    private NormalizedSource creator(String uid) {
        return new NormalizedSource(SourceType.CREATOR, uid, "https://space.bilibili.com/" + uid);
    }

    private URI parseUriIfPresent(String input) {
        if (!input.contains("://")) {
            return null;
        }
        return requireUri(input);
    }

    private URI requireUri(String input) {
        try {
            URI uri = new URI(input);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw invalidUrl();
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
    }

    private void requireHost(URI uri, Set<String> allowedHosts) {
        if (!allowedHosts.contains(normalizedHost(uri))) {
            throw new DomainException("SOURCE_HOST_NOT_ALLOWED", "仅允许使用受支持的 Bilibili 域名");
        }
    }

    private String normalizedHost(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private DomainException invalidUrl() {
        return new DomainException("INVALID_SOURCE_URL", "来源链接格式无效，必须使用 HTTPS Bilibili 链接");
    }
}
