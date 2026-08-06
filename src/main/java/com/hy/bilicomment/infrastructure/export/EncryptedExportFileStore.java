package com.hy.bilicomment.infrastructure.export;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import jakarta.annotation.PostConstruct;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.security.GeneralSecurityException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class EncryptedExportFileStore {

    private static final byte[] MAGIC = {'B', 'C', 'E', 'X'};
    private static final byte VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final Pattern FILE_KEY = Pattern.compile("^[0-9a-f]{32}$");

    private final Path directory;
    private final SecretKeySpec encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptedExportFileStore(AppProperties properties) {
        this.directory = Path.of(properties.getExports().getDirectory()).toAbsolutePath().normalize();
        this.encryptionKey = new SecretKeySpec(
                decodeKey(properties.getExports().getEncryptionKey()),
                "AES");
    }

    @PostConstruct
    void initialize() throws IOException {
        Files.createDirectories(
                directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("EXPORT_STORAGE_PATH must be a private directory, not a symlink");
        }
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }

    public EncryptedWriter create(String fileKey, long maximumPlaintextBytes) throws IOException {
        validateFileKey(fileKey);
        Path partial = resolve(fileKey + ".part");
        Path completed = resolve(fileKey + ".enc");
        if (Files.exists(completed, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(partial, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Export file key already exists");
        }

        SeekableByteChannel channel = Files.newByteChannel(
                partial,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DigestOutputStream encryptedOutput =
                    new DigestOutputStream(Channels.newOutputStream(channel), digest);
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            byte[] header = header(iv);
            encryptedOutput.write(header);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(header);
            CipherOutputStream cipherOutput = new CipherOutputStream(encryptedOutput, cipher);
            LimitedOutputStream plaintext = new LimitedOutputStream(cipherOutput, maximumPlaintextBytes);
            return new EncryptedWriter(
                    partial,
                    completed,
                    plaintext,
                    digest);
        } catch (GeneralSecurityException | IOException exception) {
            channel.close();
            Files.deleteIfExists(partial);
            throw new IOException("Unable to initialize encrypted export file", exception);
        }
    }

    public InputStream open(String fileKey, String expectedEncryptedSha256) throws IOException {
        validateFileKey(fileKey);
        if (expectedEncryptedSha256 == null
                || !expectedEncryptedSha256.matches("^[0-9a-f]{64}$")) {
            throw new DomainException("EXPORT_FILE_INVALID", "导出文件验证信息无效");
        }
        Path file = resolve(fileKey + ".enc");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new DomainException("EXPORT_FILE_NOT_FOUND", "导出文件不存在或已清理");
        }
        SeekableByteChannel channel = Files.newByteChannel(
                file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        try {
            String actual = sha256(channel);
            if (!MessageDigest.isEqual(
                    actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    expectedEncryptedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new DomainException("EXPORT_FILE_TAMPERED", "导出文件完整性验证失败");
            }
            channel.position(0);
            InputStream input = Channels.newInputStream(channel);
            byte[] header = input.readNBytes(MAGIC.length + 2 + IV_LENGTH);
            if (header.length != MAGIC.length + 2 + IV_LENGTH
                    || !MessageDigest.isEqual(
                            MAGIC,
                            java.util.Arrays.copyOfRange(header, 0, MAGIC.length))
                    || header[MAGIC.length] != VERSION
                    || Byte.toUnsignedInt(header[MAGIC.length + 1]) != IV_LENGTH) {
                throw new DomainException("EXPORT_FILE_INVALID", "导出文件头无效");
            }
            byte[] iv = java.util.Arrays.copyOfRange(header, MAGIC.length + 2, header.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(header);
            return new CipherInputStream(input, cipher);
        } catch (GeneralSecurityException | RuntimeException | IOException exception) {
            channel.close();
            if (exception instanceof DomainException domainException) {
                throw domainException;
            }
            throw new IOException("Unable to decrypt export file", exception);
        }
    }

    public void delete(String fileKey) throws IOException {
        if (fileKey == null) {
            return;
        }
        validateFileKey(fileKey);
        Files.deleteIfExists(resolve(fileKey + ".part"));
        Files.deleteIfExists(resolve(fileKey + ".enc"));
    }

    private Path resolve(String name) {
        Path resolved = directory.resolve(name).normalize();
        if (!resolved.getParent().equals(directory)) {
            throw new IllegalArgumentException("Invalid export file path");
        }
        return resolved;
    }

    private void validateFileKey(String fileKey) {
        if (fileKey == null || !FILE_KEY.matcher(fileKey).matches()) {
            throw new IllegalArgumentException("Invalid export file key");
        }
    }

    private byte[] header(byte[] iv) {
        ByteBuffer buffer = ByteBuffer.allocate(MAGIC.length + 2 + iv.length);
        return buffer.put(MAGIC).put(VERSION).put((byte) iv.length).put(iv).array();
    }

    private String sha256(SeekableByteChannel channel) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int read;
            while ((read = channel.read(buffer)) >= 0) {
                if (read > 0) {
                    buffer.flip();
                    digest.update(buffer);
                    buffer.clear();
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] decodeKey(String configured) {
        try {
            byte[] key = Base64.getDecoder().decode(configured == null ? "" : configured);
            if (key.length != 32) {
                throw new IllegalStateException("EXPORT_ENCRYPTION_KEY must decode to exactly 32 bytes");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("EXPORT_ENCRYPTION_KEY must be valid Base64", exception);
        }
    }

    public final class EncryptedWriter implements AutoCloseable {
        private final Path partial;
        private final Path completed;
        private final LimitedOutputStream output;
        private final MessageDigest digest;
        private boolean finished;

        private EncryptedWriter(
                Path partial,
                Path completed,
                LimitedOutputStream output,
                MessageDigest digest) {
            this.partial = partial;
            this.completed = completed;
            this.output = output;
            this.digest = digest;
        }

        public OutputStream outputStream() {
            return output;
        }

        public long plaintextBytes() {
            return output.count;
        }

        public Artifact finish() throws IOException {
            if (finished) {
                throw new IllegalStateException("Encrypted export writer is already finished");
            }
            output.close();
            try {
                Files.move(partial, completed, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                try {
                    Files.move(partial, completed);
                } catch (IOException moveFailure) {
                    Files.deleteIfExists(partial);
                    throw moveFailure;
                }
            } catch (IOException exception) {
                Files.deleteIfExists(partial);
                throw exception;
            }
            finished = true;
            return new Artifact(
                    completed.getFileName().toString(),
                    output.count,
                    HexFormat.of().formatHex(digest.digest()));
        }

        @Override
        public void close() throws IOException {
            if (!finished) {
                try {
                    output.close();
                } finally {
                    Files.deleteIfExists(partial);
                }
            }
        }
    }

    public void deleteOrphans(Set<String> referencedFileKeys, Instant olderThan) throws IOException {
        Set<String> referenced = referencedFileKeys == null ? Set.of() : Set.copyOf(referencedFileKeys);
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String fileKey = orphanCandidateKey(name);
                if (fileKey == null || referenced.contains(fileKey)) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        file,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()
                        && attributes.lastModifiedTime().toInstant().isBefore(olderThan)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private String orphanCandidateKey(String name) {
        if (name == null || !(name.endsWith(".part") || name.endsWith(".enc"))) {
            return null;
        }
        int suffixLength = name.endsWith(".part") ? 5 : 4;
        String fileKey = name.substring(0, name.length() - suffixLength);
        return FILE_KEY.matcher(fileKey).matches() ? fileKey : null;
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long maximumBytes;
        private long count;

        private LimitedOutputStream(OutputStream output, long maximumBytes) {
            super(output);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            out.write(bytes, offset, length);
            count += length;
        }

        private void ensureCapacity(int additional) throws ExportSizeLimitException {
            if (additional < 0 || count > maximumBytes - additional) {
                throw new ExportSizeLimitException();
            }
        }
    }

    public record Artifact(String fileName, long plaintextBytes, String encryptedSha256) {}

    public static final class ExportSizeLimitException extends IOException {
        public ExportSizeLimitException() {
            super("Export plaintext byte limit exceeded");
        }
    }
}
