package com.hy.bilicomment.application.comment;

import com.hy.bilicomment.domain.comment.CommentExportColumn;
import com.hy.bilicomment.domain.comment.CommentExportFormat;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository.CommentView;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

final class CommentExportEncoder implements AutoCloseable {

    private final CommentExportFormat format;
    private final List<CommentExportColumn> columns;
    private final BufferedWriter writer;
    private final ObjectMapper objectMapper;

    CommentExportEncoder(
            CommentExportFormat format,
            List<CommentExportColumn> columns,
            OutputStream output,
            ObjectMapper objectMapper) throws IOException {
        this.format = format;
        this.columns = List.copyOf(columns);
        this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 32 * 1024);
        this.objectMapper = objectMapper;
        if (format == CommentExportFormat.CSV) {
            writeCsvValues(columns.stream().map(this::header).toList());
        }
    }

    void write(CommentView comment) throws IOException {
        if (format == CommentExportFormat.CSV) {
            writeCsvValues(columns.stream()
                    .map(column -> csvValue(column, comment))
                    .toList());
            return;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        for (CommentExportColumn column : columns) {
            value.put(header(column), rawValue(column, comment));
        }
        writer.write(objectMapper.writeValueAsString(value));
        writer.newLine();
    }

    void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.flush();
    }

    private void writeCsvValues(List<String> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(escapeCsv(values.get(index)));
        }
        writer.write("\r\n");
    }

    private String escapeCsv(String input) {
        String value = protectSpreadsheet(input == null ? "" : input);
        if (value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private String protectSpreadsheet(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return switch (value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r', '\n' -> "'" + value;
            default -> value;
        };
    }

    private String csvValue(CommentExportColumn column, CommentView comment) {
        Object value = rawValue(column, comment);
        return value == null ? "" : value.toString();
    }

    private Object rawValue(CommentExportColumn column, CommentView comment) {
        return switch (column) {
            case RPID -> Long.toString(comment.rpid());
            case PARENT_RPID -> comment.parentRpid() == null
                    ? null
                    : Long.toString(comment.parentRpid());
            case MID -> comment.mid();
            case UNAME -> comment.uname();
            case AVATAR -> comment.avatar();
            case CURRENT_LEVEL -> comment.currentLevel();
            case CONTENT -> comment.content();
            case CTIME -> instant(comment.ctime());
        };
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private String header(CommentExportColumn column) {
        return column.name().toLowerCase(java.util.Locale.ROOT);
    }
}
