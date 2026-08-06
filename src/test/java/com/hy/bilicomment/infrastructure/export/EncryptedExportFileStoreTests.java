package com.hy.bilicomment.infrastructure.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedExportFileStoreTests {

    private static final String KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";

    @TempDir
    private Path directory;

    @Test
    void encryptsAtRestAndDecryptsOnlyWithTheRecordedDigest() throws Exception {
        EncryptedExportFileStore store = store();
        String fileKey = "0123456789abcdef0123456789abcdef";
        byte[] plaintext = "secret-comment,=HYPERLINK(\"bad\")\n".getBytes(StandardCharsets.UTF_8);

        EncryptedExportFileStore.Artifact artifact;
        try (var writer = store.create(fileKey, 1024)) {
            writer.outputStream().write(plaintext);
            artifact = writer.finish();
        }

        byte[] encrypted = Files.readAllBytes(directory.resolve(fileKey + ".enc"));
        assertThat(indexOf(encrypted, plaintext)).isEqualTo(-1);
        assertThat(artifact.fileName()).isEqualTo(fileKey + ".enc");
        try (var input = store.open(fileKey, artifact.encryptedSha256())) {
            assertThat(input.readAllBytes()).isEqualTo(plaintext);
        }
    }

    @Test
    void rejectsCiphertextTamperingBeforeServingTheFile() throws Exception {
        EncryptedExportFileStore store = store();
        String fileKey = "1123456789abcdef0123456789abcdef";
        EncryptedExportFileStore.Artifact artifact;
        try (var writer = store.create(fileKey, 1024)) {
            writer.outputStream().write("sensitive".getBytes(StandardCharsets.UTF_8));
            artifact = writer.finish();
        }
        Path encryptedFile = directory.resolve(fileKey + ".enc");
        byte[] encrypted = Files.readAllBytes(encryptedFile);
        encrypted[encrypted.length - 1] ^= 1;
        Files.write(encryptedFile, encrypted);

        assertThatThrownBy(() -> store.open(fileKey, artifact.encryptedSha256()))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("EXPORT_FILE_TAMPERED"));

        String alteredDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(encrypted));
        assertThatThrownBy(() -> {
                    try (var input = store.open(fileKey, alteredDigest)) {
                        input.readAllBytes();
                    }
                })
                .isInstanceOf(IOException.class);
    }

    @Test
    void anOpenedDownloadSurvivesConcurrentExpiryCleanup() throws Exception {
        EncryptedExportFileStore store = store();
        String fileKey = "4123456789abcdef0123456789abcdef";
        byte[] plaintext = "download already authorized".getBytes(StandardCharsets.UTF_8);
        EncryptedExportFileStore.Artifact artifact;
        try (var writer = store.create(fileKey, 1024)) {
            writer.outputStream().write(plaintext);
            artifact = writer.finish();
        }

        try (var input = store.open(fileKey, artifact.encryptedSha256())) {
            store.delete(fileKey);
            assertThat(input.readAllBytes()).isEqualTo(plaintext);
        }
        assertThat(directory.resolve(fileKey + ".enc")).doesNotExist();
    }

    @Test
    void removesPartialFilesAfterLimitsOrFinalMoveFailures() throws Exception {
        EncryptedExportFileStore store = store();
        String limitedKey = "2123456789abcdef0123456789abcdef";

        assertThatThrownBy(() -> {
                    try (var writer = store.create(limitedKey, 3)) {
                        writer.outputStream().write("four".getBytes(StandardCharsets.UTF_8));
                    }
                })
                .isInstanceOf(EncryptedExportFileStore.ExportSizeLimitException.class);
        assertThat(directory.resolve(limitedKey + ".part")).doesNotExist();

        String failedMoveKey = "3123456789abcdef0123456789abcdef";
        try (var writer = store.create(failedMoveKey, 1024)) {
            writer.outputStream().write("payload".getBytes(StandardCharsets.UTF_8));
            Files.createDirectory(directory.resolve(failedMoveKey + ".enc"));
            assertThatThrownBy(writer::finish).isInstanceOf(IOException.class);
        }
        assertThat(directory.resolve(failedMoveKey + ".part")).doesNotExist();
    }

    private EncryptedExportFileStore store() throws IOException {
        AppProperties properties = new AppProperties();
        properties.getExports().setDirectory(directory.toString());
        properties.getExports().setEncryptionKey(KEY);
        EncryptedExportFileStore store = new EncryptedExportFileStore(properties);
        store.initialize();
        return store;
    }

    private int indexOf(byte[] haystack, byte[] needle) {
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[start + offset] != needle[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return start;
            }
        }
        return -1;
    }
}
