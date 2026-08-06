package com.hy.bilicomment.application.comment;

import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentReplyScope;
import com.hy.bilicomment.domain.error.DomainException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class CommentFilterService {

    private static final Pattern MID = Pattern.compile("^[1-9][0-9]{0,31}$");

    public CommentFilter normalize(CommentFilter input) {
        CommentFilter filter = input == null
                ? new CommentFilter(null, null, null, null, null, false,
                        null, null, null, null, CommentReplyScope.ALL)
                : new CommentFilter(
                        text(input.keyword()),
                        text(input.mid()),
                        text(input.uname()),
                        input.levelMin(),
                        input.levelMax(),
                        input.unknownLevelOnly(),
                        input.ctimeFrom(),
                        input.ctimeBefore(),
                        input.rpid(),
                        input.parentRpid(),
                        input.replyScope() == null ? CommentReplyScope.ALL : input.replyScope());
        validate(filter);
        return filter;
    }

    private void validate(CommentFilter filter) {
        if (filter.keyword() != null && filter.keyword().length() > 200) {
            throw invalid("评论关键字不能超过 200 个字符");
        }
        if (filter.uname() != null && filter.uname().length() > 255) {
            throw invalid("用户昵称不能超过 255 个字符");
        }
        if (filter.mid() != null && !MID.matcher(filter.mid()).matches()) {
            throw invalid("MID 必须是正整数字符串");
        }
        if (filter.levelMin() != null && (filter.levelMin() < 0 || filter.levelMin() > 6)
                || filter.levelMax() != null && (filter.levelMax() < 0 || filter.levelMax() > 6)
                || filter.levelMin() != null && filter.levelMax() != null
                        && filter.levelMin() > filter.levelMax()) {
            throw invalid("用户等级范围必须在 0 到 6 之间");
        }
        if (filter.unknownLevelOnly() && (filter.levelMin() != null || filter.levelMax() != null)) {
            throw invalid("未知等级不能与等级范围组合");
        }
        if (filter.ctimeFrom() != null && filter.ctimeBefore() != null
                && !filter.ctimeFrom().isBefore(filter.ctimeBefore())) {
            throw invalid("评论开始时间必须早于结束时间");
        }
        if (filter.rpid() != null && filter.rpid() <= 0
                || filter.parentRpid() != null && filter.parentRpid() <= 0) {
            throw invalid("评论 ID 必须是正整数");
        }
        if (filter.parentRpid() != null && filter.replyScope() != CommentReplyScope.REPLIES) {
            throw invalid("指定父评论时 replyScope 必须为 REPLIES");
        }
        if ((tooShort(filter.keyword()) || tooShort(filter.uname())) && !hasSelectiveBoundary(filter)) {
            throw new DomainException(
                    "COMMENT_FILTER_TOO_BROAD",
                    "少于 3 个字符的模糊搜索必须同时指定 MID、评论 ID、父评论或时间范围");
        }
    }

    private boolean tooShort(String value) {
        return value != null && value.codePointCount(0, value.length()) < 3;
    }

    private boolean hasSelectiveBoundary(CommentFilter filter) {
        return filter.mid() != null
                || filter.rpid() != null
                || filter.parentRpid() != null
                || filter.ctimeFrom() != null
                || filter.ctimeBefore() != null;
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private DomainException invalid(String message) {
        return new DomainException("COMMENT_FILTER_INVALID", message);
    }
}
