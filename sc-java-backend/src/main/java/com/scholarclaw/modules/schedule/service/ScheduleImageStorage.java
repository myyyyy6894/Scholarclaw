package com.scholarclaw.modules.schedule.service;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Schedule-owned image storage. Callers enforce ownership, consent and lifecycle. */
@Service
public class ScheduleImageStorage {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final long MAX_PIXELS = 20_000_000L;
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final String NAME_PATTERN = "[0-9a-f]{32}\\.(png|jpg)";
    private final Path root;
    private final Semaphore decodePermits = new Semaphore(2);

    public ScheduleImageStorage(@Value("${sc.storage.root:/data/scholarclaw/storage}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    /** Bytes are re-encoded from decoded pixels: metadata and trailing payloads are not preserved. */
    public ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw invalid(ErrorCode.FILE_SIZE_EXCEEDED, "图片须非空且不超过10 MiB。");
        }
        String originalName = file.getOriginalFilename();
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        boolean namedPng = lowerName.endsWith(".png");
        boolean namedJpeg = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg");
        if (!namedPng && !namedJpeg) throw invalid(ErrorCode.FILE_TYPE_UNSUPPORTED, "仅支持PNG或JPEG图片。");
        if (!decodePermits.tryAcquire()) throw invalid(ErrorCode.SCHEDULE_IMAGE_BUSY, "图片处理繁忙，请稍后重试。");
        boolean handedOff = false;
        byte[] bytes;
        try {
            try (InputStream input = file.getInputStream()) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            } catch (IOException e) {
                throw invalid(ErrorCode.FILE_MAGIC_INVALID, "图片读取失败，请重新上传。");
            }
            try {
                if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                    throw invalid(ErrorCode.FILE_SIZE_EXCEEDED, "图片须非空且不超过10 MiB。");
                }
                boolean isPng = startsWith(bytes, PNG);
                boolean isJpeg = bytes.length >= 3 && bytes[0] == (byte) 0xff
                        && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
                if ((namedPng && !isPng) || (namedJpeg && !isJpeg)) {
                    throw invalid(ErrorCode.FILE_MAGIC_INVALID, "图片内容与文件类型不一致。");
                }
                if (isPng) checkPngChunks(bytes);
                byte[] clean = decodeAndEncode(bytes, isPng ? "png" : "jpeg");
                ValidatedImage result = new ValidatedImage(clean, sha256(bytes), isPng ? "image/png" : "image/jpeg",
                        isPng ? ".png" : ".jpg", decodePermits);
                handedOff = true;
                return result;
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } finally {
            if (!handedOff) decodePermits.release();
        }
    }

    /** Generates the only filename form accepted by Python; caller compensates a failed DB commit. */
    public String store(long userId, ValidatedImage image) {
        if (userId < 1 || image == null) throw new IllegalArgumentException("非法图片所属用户");
        byte[] content = image.bytes();
        Path directory = userDirectory(userId, true);
        String name = UUID.randomUUID().toString().replace("-", "") + image.extension();
        try {
            Path target = directory.resolve(name);
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                out.write(content);
            } catch (IOException | RuntimeException e) {
                try { Files.deleteIfExists(target); }
                catch (IOException cleanupError) { e.addSuppressed(cleanupError); }
                throw e;
            }
            return name;
        } catch (IOException e) {
            throw new IllegalStateException("临时课表图片保存失败", e);
        }
    }

    /** Caller must first check the task's owner, state and expiry. */
    public byte[] read(long userId, String name) {
        Path path = existingPath(userId, name);
        if (path == null) throw invalid(ErrorCode.RESOURCE_NOT_FOUND, "图片不存在或已清理。");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw invalid(ErrorCode.RESOURCE_NOT_FOUND, "图片不存在或已清理。");
        }
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("invalid size");
            return bytes;
        } catch (IOException e) {
            throw invalid(ErrorCode.RESOURCE_NOT_FOUND, "图片不存在或已清理。");
        }
    }

    /** Idempotent deletion; failure must be persisted for retry by the caller. */
    public void delete(long userId, String name) {
        Path path = existingPath(userId, name);
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("临时课表图片删除失败", e);
        }
    }

    /** Account-deactivation cleanup; never recurse through foreign directories or symlinks. */
    public void deleteUserImages(long userId) {
        Path directory = userDirectory(userId, false);
        if (directory == null) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (!entry.getFileName().toString().matches(NAME_PATTERN)
                        || Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("unexpected entry");
                }
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            throw new IllegalStateException("用户课表图片清理失败", e);
        }
    }

    private Path existingPath(long userId, String name) {
        if (userId < 1 || name == null || !name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("非法课表图片标识");
        }
        Path directory = userDirectory(userId, false);
        return directory == null ? null : directory.resolve(name);
    }

    private Path userDirectory(long userId, boolean create) {
        if (userId < 1) throw new IllegalArgumentException("非法图片所属用户");
        try {
            if (create) Files.createDirectories(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                if (!create && !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return null;
                throw new IOException("invalid root");
            }
            Path current = root;
            for (String component : new String[]{"raw", "schedule-import", Long.toString(userId)}) {
                current = current.resolve(component);
                if (create) {
                    try {
                        Files.createDirectory(current, PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
                    } catch (FileAlreadyExistsException ignored) { /* Check existing entry below. */ }
                }
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (!create && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return null;
                    throw new IOException("invalid directory");
                }
            }
            return current;
        } catch (IOException e) {
            throw new IllegalStateException("课表图片目录不可用", e);
        }
    }

    private static byte[] decodeAndEncode(byte[] bytes, String expectedFormat) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("no reader");
            ImageReader reader = readers.next();
            try {
                if (!expectedFormat.equalsIgnoreCase(reader.getFormatName())) throw new IOException("format mismatch");
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS
                        || reader.getNumImages(true) != 1) throw new IOException("invalid dimensions or frames");
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("decode failed");
                return encode(image, expectedFormat);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw invalid(ErrorCode.FILE_MAGIC_INVALID, "图片损坏、包含动画或超过2000万像素，请重新上传。");
        }
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) throw new IOException("no writer");
        ImageWriter writer = writers.next();
        var buffer = new ByteArrayOutputStream();
        try (var limited = new LimitedOutput(buffer); var output = new MemoryCacheImageOutputStream(limited)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), writer.getDefaultWriteParam());
            output.flush();
            return buffer.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** APNG can appear as one frame to stock ImageIO; inspect chunk framing explicitly. */
    private static void checkPngChunks(byte[] bytes) {
        int offset = PNG.length;
        while (offset <= bytes.length - 12) {
            long size = ((long) (bytes[offset] & 255) << 24) | ((long) (bytes[offset + 1] & 255) << 16)
                    | ((long) (bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
            long end = (long) offset + 12 + size;
            if (end > bytes.length) break;
            String type = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("acTL".equals(type)) throw invalid(ErrorCode.FILE_MAGIC_INVALID, "不支持动画图片。");
            if ("IEND".equals(type)) {
                if (size == 0 && end == bytes.length) return;
                break;
            }
            offset = (int) end;
        }
        throw invalid(ErrorCode.FILE_MAGIC_INVALID, "图片数据损坏，请重新上传。");
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static BusinessException invalid(ErrorCode code, String message) { return new BusinessException(code, message); }

    /** Must be closed after saving or rejecting the upload, releasing its bounded memory slot. */
    public static final class ValidatedImage implements AutoCloseable {
        private final byte[] bytes;
        private final String originalHash;
        private final String mediaType;
        private final String extension;
        private final Semaphore permits;
        private final AtomicBoolean closed = new AtomicBoolean();
        private ValidatedImage(byte[] bytes, String originalHash, String mediaType, String extension, Semaphore permits) {
            this.bytes = bytes; this.originalHash = originalHash; this.mediaType = mediaType;
            this.extension = extension; this.permits = permits;
        }
        byte[] bytes() {
            if (closed.get()) throw new IllegalStateException("图片内容已释放");
            return bytes;
        }
        public String originalHash() { return originalHash; }
        public String mediaType() { return mediaType; }
        public String extension() { return extension; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                Arrays.fill(bytes, (byte) 0);
                permits.release();
            }
        }
    }

    private static final class LimitedOutput extends OutputStream {
        private final OutputStream target;
        private int count;
        LimitedOutput(OutputStream target) { this.target = target; }
        @Override public void write(int value) throws IOException { ensure(1); target.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            ensure(length); target.write(bytes, offset, length);
        }
        private void ensure(int length) throws IOException {
            if (length > MAX_BYTES - count) throw new IOException("encoded image too large");
            count += length;
        }
    }
}
