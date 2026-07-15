package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
public class FileStorageSupport {

    record UploadConstraints(
            long maxSize,
            Set<String> allowedExtensions,
            String missingMessage,
            String sizeMessage,
            String typeMessage
    ) {
    }

    record StoredFile(
            Path filePath,
            String storedFileName,
            String originalName,
            String contentType,
            long fileSize,
            String fileExtension
    ) {
    }

    StoredFile store(
            MultipartFile file,
            Path root,
            String storedFileName,
            String originalName,
            UploadConstraints constraints,
            String invalidPathMessage,
            String failureMessage
    ) {
        ensureFilePresent(file, constraints.missingMessage());
        String extension = validateUpload(file, originalName, constraints);
        Path target = resolveInRoot(root, storedFileName, invalidPathMessage);
        Path normalizedRoot = root.toAbsolutePath().normalize();

        Path tempFile = null;
        try {
            Files.createDirectories(normalizedRoot);
            tempFile = Files.createTempFile(normalizedRoot, tempPrefix(storedFileName), ".tmp");
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            validateSignature(tempFile, extension, constraints.typeMessage());
            moveIntoPlace(tempFile, target);
            return new StoredFile(
                    target,
                    target.getFileName().toString(),
                    originalName,
                    canonicalContentType(extension),
                    file.getSize(),
                    extension
            );
        } catch (RuntimeException e) {
            deleteQuietly(tempFile, "Failed to delete rejected upload");
            throw e;
        } catch (IOException e) {
            deleteQuietly(tempFile, "Failed to delete temp upload");
            throw new BusinessException(ResultCode.SYSTEM_ERROR, failureMessage);
        }
    }

    Path storageRoot(String configuredDirectory) {
        return Paths.get(configuredDirectory).toAbsolutePath().normalize();
    }

    String cleanOriginalName(String originalFilename, String fallbackName, String invalidMessage) {
        String originalName = StringUtils.cleanPath(Objects.requireNonNullElse(originalFilename, fallbackName)).trim();
        if (originalName.isBlank() || originalName.contains("..") || originalName.contains("/") || originalName.contains("\\")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, invalidMessage);
        }
        return originalName;
    }

    Path resolveInRoot(Path root, String storedFileName, String invalidPathMessage) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String cleanName = StringUtils.cleanPath(Objects.requireNonNullElse(storedFileName, "")).trim();
        Path filePath = normalizedRoot.resolve(cleanName).normalize();
        if (cleanName.isBlank() || !filePath.startsWith(normalizedRoot)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, invalidPathMessage);
        }
        return filePath;
    }

    void registerStoredFileLifecycle(Path newFile, Runnable afterCommitAction, String rollbackMessage) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                afterCommitAction.run();
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(newFile, rollbackMessage);
                }
            }
        });
    }

    void registerRollbackCleanup(Path file, String message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(file, message);
                }
            }
        });
    }

    void registerAfterCommit(Runnable runnable) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runnable.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runnable.run();
            }
        });
    }

    void deleteQuietly(Path path, String message) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("{}: {}", message, path, e);
        }
    }

    boolean isPreviewable(String contentType, String originalName) {
        String extension = StringUtils.getFilenameExtension(originalName);
        if (extension == null) {
            return false;
        }
        String normalizedExtension = extension.toLowerCase(Locale.ROOT);
        String normalizedContentType = firstNonBlank(contentType, "").toLowerCase(Locale.ROOT);
        if ("pdf".equals(normalizedExtension)) {
            return "application/pdf".equals(normalizedContentType);
        }
        return switch (normalizedExtension) {
            case "jpg", "jpeg" -> Set.of("image/jpeg", "image/jpg").contains(normalizedContentType);
            case "png" -> "image/png".equals(normalizedContentType);
            case "webp" -> "image/webp".equals(normalizedContentType);
            case "gif" -> "image/gif".equals(normalizedContentType);
            case "bmp" -> "image/bmp".equals(normalizedContentType);
            default -> false;
        };
    }

    String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String validateUpload(MultipartFile file, String originalName, UploadConstraints constraints) {
        if (file.getSize() > constraints.maxSize()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, constraints.sizeMessage());
        }
        String extension = StringUtils.getFilenameExtension(originalName);
        String normalizedExtension = extension == null ? null : extension.toLowerCase(Locale.ROOT);
        if (normalizedExtension == null || !constraints.allowedExtensions().contains(normalizedExtension)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, constraints.typeMessage());
        }
        if (!declaredMimeMatches(normalizedExtension, file.getContentType())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, constraints.typeMessage());
        }
        return normalizedExtension;
    }

    private void validateSignature(Path file, String extension, String typeMessage) throws IOException {
        byte[] header = new byte[16];
        int read;
        try (InputStream inputStream = Files.newInputStream(file)) {
            read = inputStream.read(header);
        }
        if (!signatureMatches(file, extension, header, Math.max(read, 0))) {
            throw new BusinessException(ResultCode.PARAM_ERROR, typeMessage);
        }
    }

    private boolean signatureMatches(Path file, String extension, byte[] header, int length) throws IOException {
        return switch (extension) {
            case "pdf" -> startsWith(header, length, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "jpg", "jpeg" -> length >= 3
                    && unsigned(header[0]) == 0xFF
                    && unsigned(header[1]) == 0xD8
                    && unsigned(header[2]) == 0xFF;
            case "png" -> startsWith(header, length,
                    new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "gif" -> startsWith(header, length, "GIF87a".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    || startsWith(header, length, "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "bmp" -> startsWith(header, length, new byte[]{0x42, 0x4D});
            case "webp" -> length >= 12
                    && startsWith(header, length, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            case "doc", "xls" -> startsWith(header, length,
                    new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
            case "docx", "xlsx", "ofd" -> startsWith(header, length, new byte[]{0x50, 0x4B, 0x03, 0x04})
                    || startsWith(header, length, new byte[]{0x50, 0x4B, 0x05, 0x06})
                    || startsWith(header, length, new byte[]{0x50, 0x4B, 0x07, 0x08});
            case "csv" -> isTextFile(file);
            default -> false;
        };
    }

    private boolean declaredMimeMatches(String extension, String contentType) {
        String mime = firstNonBlank(contentType, "application/octet-stream").toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(mime)) {
            return true;
        }
        return switch (extension) {
            case "pdf" -> "application/pdf".equals(mime);
            case "jpg", "jpeg" -> Set.of("image/jpeg", "image/jpg", "image/pjpeg").contains(mime);
            case "png" -> "image/png".equals(mime);
            case "gif" -> "image/gif".equals(mime);
            case "bmp" -> Set.of("image/bmp", "image/x-ms-bmp").contains(mime);
            case "webp" -> "image/webp".equals(mime);
            case "doc" -> "application/msword".equals(mime);
            case "docx" -> Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/zip"
            ).contains(mime);
            case "xls" -> "application/vnd.ms-excel".equals(mime);
            case "xlsx" -> Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "application/zip"
            ).contains(mime);
            case "ofd" -> Set.of("application/ofd", "application/zip").contains(mime);
            case "csv" -> Set.of("text/csv", "text/plain", "application/vnd.ms-excel").contains(mime);
            default -> false;
        };
    }

    private String canonicalContentType(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ofd" -> "application/ofd";
            case "csv" -> "text/csv";
            default -> "application/octet-stream";
        };
    }

    private boolean startsWith(byte[] value, int valueLength, byte[] prefix) {
        if (valueLength < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }

    private boolean isTextFile(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        for (byte value : content) {
            if (value == 0) {
                return false;
            }
        }
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException ignored) {
            return false;
        }
    }

    private void ensureFilePresent(MultipartFile file, String missingMessage) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, missingMessage);
        }
    }

    private void moveIntoPlace(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String tempPrefix(String storedFileName) {
        String prefix = StringUtils.cleanPath(Objects.requireNonNullElse(storedFileName, "upload"))
                .replace("/", "-")
                .replace("\\", "-");
        return prefix.length() < 3 ? "upload-" + prefix : prefix + "-";
    }

    private String probeContentType(Path target) {
        try {
            return Files.probeContentType(target);
        } catch (IOException e) {
            return null;
        }
    }
}
