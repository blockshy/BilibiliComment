package com.hy.bilicomment.infrastructure.bilibili;

import com.hy.bilicomment.domain.comment.CommentRecord;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.SourceType;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class BilibiliClient {

    private static final String API = "https://api.bilibili.com";

    private final BilibiliHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final WbiSigner signer;
    private final Clock clock;
    private volatile CachedWbiKeys cachedWbiKeys;

    public BilibiliClient(
            BilibiliHttpClient httpClient,
            ObjectMapper objectMapper,
            WbiSigner signer,
            Clock clock) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.signer = signer;
        this.clock = clock;
    }

    public boolean validateCredential(BilibiliCredential credential) {
        JsonNode data = apiGet("/x/web-interface/nav", Map.of(), credential);
        return data.path("isLogin").asBoolean(false);
    }

    public ResolvedContent resolveVideo(String bvid, BilibiliCredential credential) {
        JsonNode data = apiGet(
                "/x/web-interface/view",
                Map.of("bvid", bvid),
                credential);
        long aid = data.path("aid").asLong(0);
        if (aid <= 0) {
            throw new DomainException("VIDEO_OID_MISSING", "无法解析视频评论参数");
        }
        String title = textOr(data.path("title"), bvid);
        return new ResolvedContent(SourceType.VIDEO, bvid, Long.toString(aid), "1", title);
    }

    public ResolvedContent resolveDynamic(String dynamicId, BilibiliCredential credential) {
        JsonNode data = apiGet(
                "/x/polymer/web-dynamic/v1/detail",
                Map.of("id", dynamicId),
                credential);
        JsonNode item = data.path("item");
        JsonNode basic = item.path("basic");
        String commentType = basic.path("comment_type").asText("");
        String oid = basic.path("comment_id_str").asText("");
        if (oid.isBlank() && "17".equals(commentType)) {
            oid = dynamicId;
        }
        if (oid.isBlank() || commentType.isBlank()) {
            throw new DomainException("DYNAMIC_OID_MISSING", "该动态类型缺少可用的评论参数");
        }
        String title = firstNonBlank(
                item.path("modules").path("module_dynamic").path("major").path("archive").path("title").asText(""),
                item.path("modules").path("module_author").path("name").asText(""),
                "动态 " + dynamicId);
        return new ResolvedContent(SourceType.DYNAMIC, dynamicId, oid, commentType, title);
    }

    public List<DiscoveredContent> discoverCreatorContent(String uid, BilibiliCredential credential) {
        JsonNode data = apiGet(
                "/x/polymer/web-dynamic/v1/feed/space",
                Map.of("host_mid", uid),
                credential);
        JsonNode items = data.path("items");
        if (!items.isArray()) {
            throw new DomainException("CREATOR_FEED_INVALID", "UP 主内容列表响应格式无效");
        }
        List<DiscoveredContent> discovered = new ArrayList<>();
        for (JsonNode item : items) {
            String dynamicType = item.path("type").asText("");
            if ("DYNAMIC_TYPE_AV".equals(dynamicType)) {
                JsonNode archive = item.path("modules").path("module_dynamic").path("major").path("archive");
                String bvid = archive.path("bvid").asText("");
                if (!bvid.isBlank()) {
                    discovered.add(new DiscoveredContent(
                            SourceType.VIDEO,
                            bvid,
                            textOr(archive.path("title"), bvid),
                            archive.path("aid").asText(""),
                            "1"));
                }
                continue;
            }
            if (!isSupportedDynamicType(dynamicType)) {
                continue;
            }
            String id = item.path("id_str").asText("");
            JsonNode basic = item.path("basic");
            String oid = basic.path("comment_id_str").asText("");
            String commentType = basic.path("comment_type").asText("");
            if (!id.isBlank() && !oid.isBlank() && !commentType.isBlank()) {
                discovered.add(new DiscoveredContent(
                        SourceType.DYNAMIC,
                        id,
                        "动态 " + id,
                        oid,
                        commentType));
            }
        }
        return List.copyOf(discovered);
    }

    public CommentPage fetchCommentPage(
            ResolvedContent content,
            String cursor,
            BilibiliCredential credential) {
        WbiKeys keys = wbiKeys(credential);
        ObjectNode pagination = objectMapper.createObjectNode();
        pagination.put("offset", cursor == null ? "" : cursor);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("oid", content.oid());
        parameters.put("type", content.commentType());
        parameters.put("mode", "2");
        parameters.put("pagination_str", objectMapper.writeValueAsString(pagination));
        parameters.put("plat", "1");
        parameters.put("seek_rpid", "0");
        parameters.put("web_location", "1315875");

        WbiSigner.SignedQuery signed = signer.sign(parameters, keys.imageKey(), keys.subKey());
        JsonNode data = apiGetSigned("/x/v2/reply/wbi/main", signed.query(), credential);
        JsonNode replies = data.path("replies");
        JsonNode cursorNode = data.path("cursor");
        if (replies.isMissingNode() && cursorNode.isMissingNode()) {
            throw new DomainException("COMMENT_PAGE_INVALID", "评论响应缺少分页结构");
        }
        if (!replies.isMissingNode() && !replies.isNull() && !replies.isArray()) {
            throw new DomainException("COMMENT_PAGE_INVALID", "评论列表响应格式无效");
        }
        List<CommentRecord> comments = parseComments(replies);
        boolean end = cursorNode.path("is_end").asBoolean(false);
        String nextCursor = readNextCursor(cursorNode.path("pagination_reply").path("next_offset"));
        if (nextCursor == null || nextCursor.isBlank()) {
            end = true;
        }
        return new CommentPage(comments, nextCursor, end);
    }

    private List<CommentRecord> parseComments(JsonNode replies) {
        if (!replies.isArray()) {
            return List.of();
        }
        List<CommentRecord> comments = new ArrayList<>();
        for (JsonNode reply : replies) {
            CommentRecord topLevel = parseComment(reply, null);
            if (topLevel == null) {
                continue;
            }
            comments.add(topLevel);
            JsonNode children = reply.path("replies");
            if (!children.isArray()) {
                continue;
            }
            for (JsonNode child : children) {
                CommentRecord childRecord = parseComment(child, topLevel.rpid());
                if (childRecord != null) {
                    comments.add(childRecord);
                }
            }
        }
        return List.copyOf(comments);
    }

    private CommentRecord parseComment(JsonNode reply, Long fallbackParent) {
        long rpid = reply.path("rpid").asLong(0);
        JsonNode member = reply.path("member");
        String mid = member.path("mid").asText("");
        long epochSecond = reply.path("ctime").asLong(0);
        if (rpid <= 0 || mid.isBlank() || epochSecond <= 0) {
            return null;
        }
        Long parent = positiveLong(reply.path("parent_str").asText(""));
        if (parent == null) {
            parent = positiveLong(reply.path("parent").asText(""));
        }
        if (parent == null) {
            parent = fallbackParent;
        }
        int level = member.path("level_info").path("current_level").asInt(-1);
        return new CommentRecord(
                rpid,
                mid,
                nullIfBlank(member.path("uname").asText("")),
                nullIfBlank(member.path("avatar").asText("")),
                level < 0 ? null : level,
                reply.path("content").path("message").asText(""),
                Instant.ofEpochSecond(epochSecond),
                parent);
    }

    private JsonNode apiGet(String path, Map<String, String> parameters, BilibiliCredential credential) {
        String query = parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        URI uri = URI.create(API + path + (query.isEmpty() ? "" : "?" + query));
        return checkedData(httpClient.get(uri, credential));
    }

    private JsonNode apiGetSigned(String path, String query, BilibiliCredential credential) {
        return checkedData(httpClient.get(URI.create(API + path + "?" + query), credential));
    }

    private JsonNode checkedData(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new DomainException("BILIBILI_RESPONSE_INVALID", "Bilibili 响应不是有效 JSON", exception);
        }
        if (root == null || root.isMissingNode()) {
            throw new DomainException("BILIBILI_RESPONSE_INVALID", "Bilibili 响应不是有效 JSON");
        }
        int code = root.path("code").asInt(Integer.MIN_VALUE);
        if (code != 0) {
            String message = root.path("message").asText("上游业务错误");
            if (message.length() > 160) {
                message = message.substring(0, 160);
            }
            throw new DomainException("BILIBILI_API_ERROR", "Bilibili 返回错误 " + code + "：" + message);
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new DomainException("BILIBILI_DATA_MISSING", "Bilibili 响应缺少 data");
        }
        return data;
    }

    private WbiKeys wbiKeys(BilibiliCredential credential) {
        CachedWbiKeys current = cachedWbiKeys;
        Instant now = clock.instant();
        if (current != null && current.expiresAt().isAfter(now)) {
            return current.keys();
        }
        synchronized (this) {
            current = cachedWbiKeys;
            if (current != null && current.expiresAt().isAfter(now)) {
                return current.keys();
            }
            JsonNode data = apiGet("/x/web-interface/nav", Map.of(), credential);
            JsonNode wbi = data.path("wbi_img");
            WbiKeys keys = new WbiKeys(
                    fileStem(wbi.path("img_url").asText("")),
                    fileStem(wbi.path("sub_url").asText("")));
            cachedWbiKeys = new CachedWbiKeys(keys, now.plusSeconds(30 * 60));
            return keys;
        }
    }

    private String fileStem(String url) {
        if (url.isBlank()) {
            throw new DomainException("WBI_KEY_MISSING", "Bilibili 未返回 WBI key");
        }
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (IllegalArgumentException exception) {
            throw new DomainException("WBI_KEY_INVALID", "Bilibili 返回的 WBI key 格式无效", exception);
        }
        if (path == null || path.isBlank()) {
            throw new DomainException("WBI_KEY_INVALID", "Bilibili 返回的 WBI key 格式无效");
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (slash < 0 || dot <= slash + 1) {
            throw new DomainException("WBI_KEY_INVALID", "Bilibili 返回的 WBI key 格式无效");
        }
        return path.substring(slash + 1, dot);
    }

    private String readNextCursor(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return nullIfBlank(node.asText());
        }
        try {
            String value = objectMapper.writeValueAsString(node);
            return "{}".equals(value) ? null : value;
        } catch (Exception exception) {
            throw new DomainException("COMMENT_CURSOR_INVALID", "评论分页游标无法解析", exception);
        }
    }

    private boolean isSupportedDynamicType(String type) {
        return switch (type) {
            case "DYNAMIC_TYPE_FORWARD", "DYNAMIC_TYPE_WORD", "DYNAMIC_TYPE_DRAW", "DYNAMIC_TYPE_ARTICLE" -> true;
            default -> false;
        };
    }

    private Long positiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String textOr(JsonNode node, String fallback) {
        String value = node.asText("");
        return value.isBlank() ? fallback : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record WbiKeys(String imageKey, String subKey) {}

    private record CachedWbiKeys(WbiKeys keys, Instant expiresAt) {}
}
