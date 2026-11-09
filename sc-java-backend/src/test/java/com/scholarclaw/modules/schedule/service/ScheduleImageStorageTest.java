package com.scholarclaw.modules.schedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.scholarclaw.common.exception.BusinessException;
import com.scholarclaw.common.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class ScheduleImageStorageTest {
    @TempDir Path root;

    @Test
    void storesReencodedPngWithoutMetadataAndIsolatesOwners() throws Exception {
        byte[] withMetadata = insertPngChunk(image("png"), "tEXt", "Comment\0private-exif-data".getBytes(StandardCharsets.UTF_8));
        var storage = new ScheduleImageStorage(root.toString());
        String name;
        try (var validated = storage.validate(file("timetable.png", withMetadata))) {
            assertThat(validated.mediaType()).isEqualTo("image/png");
            assertThat(validated.originalHash()).hasSize(64);
            name = storage.store(11, validated);
            assertThat(name).matches("[0-9a-f]{32}\\.png");
            byte[] saved = storage.read(11, name);
            assertThat(new String(saved, StandardCharsets.ISO_8859_1)).doesNotContain("private-exif-data");
            assertThat(ImageIO.read(new java.io.ByteArrayInputStream(saved))).isNotNull();
            assertThatThrownBy(() -> storage.read(12, name)).isInstanceOf(BusinessException.class);
            assertThat(root.resolve("raw/schedule-import/11").toFile().canRead()).isTrue();
        }
        storage.delete(11, name);
        storage.delete(11, name);
        assertThatThrownBy(() -> storage.read(11, name)).isInstanceOf(BusinessException.class);
    }

    @Test
    void acceptsRealJpegPixelsAndNormalizesExtension() throws Exception {
        var storage = new ScheduleImageStorage(root.toString());
        byte[] jpeg = image("jpeg");
        var tagged = new ByteArrayOutputStream();
        tagged.write(jpeg, 0, 2);
        byte[] metadata = "Exif\0\0private-jpeg-metadata".getBytes(StandardCharsets.US_ASCII);
        tagged.write(new byte[]{(byte) 0xff, (byte) 0xe1});
        tagged.write(ByteBuffer.allocate(2).putShort((short) (metadata.length + 2)).array());
        tagged.write(metadata); tagged.write(jpeg, 2, jpeg.length - 2);
        try (var validated = storage.validate(file("timetable.JPEG", tagged.toByteArray()))) {
            String name = storage.store(19, validated);
            assertThat(name).endsWith(".jpg");
            assertThat(validated.mediaType()).isEqualTo("image/jpeg");
            byte[] saved = storage.read(19, name);
            assertThat(new String(saved, StandardCharsets.ISO_8859_1)).doesNotContain("private-jpeg-metadata");
            assertThat(ImageIO.read(new java.io.ByteArrayInputStream(saved))).isNotNull();
        }
    }

    @Test
    void rejectsBadNamesSignaturesTruncationAnimationAndOversizedDimensions() throws Exception {
        var storage = new ScheduleImageStorage(root.toString());
        byte[] png = image("png");
        assertCode(() -> storage.validate(file("bad.svg", png)), ErrorCode.FILE_TYPE_UNSUPPORTED);
        assertCode(() -> storage.validate(file("bad.jpg", png)), ErrorCode.FILE_MAGIC_INVALID);
        assertCode(() -> storage.validate(file("bad.png", new byte[]{1, 2, 3})), ErrorCode.FILE_MAGIC_INVALID);
        assertCode(() -> storage.validate(file("bad.png", java.util.Arrays.copyOf(png, png.length - 3))),
                ErrorCode.FILE_MAGIC_INVALID);
        byte[] animation = insertPngChunk(png, "acTL", new byte[8]);
        assertCode(() -> storage.validate(file("bad.png", animation)), ErrorCode.FILE_MAGIC_INVALID);
        byte[] tooLarge = png.clone();
        ByteBuffer.wrap(tooLarge).putInt(16, 5000).putInt(20, 4001);
        CRC32 crc = new CRC32(); crc.update(tooLarge, 12, 17);
        ByteBuffer.wrap(tooLarge).putInt(29, (int) crc.getValue());
        assertCode(() -> storage.validate(file("big.png", tooLarge)), ErrorCode.FILE_MAGIC_INVALID);
        byte[] trailing = java.util.Arrays.copyOf(png, png.length + 1);
        assertCode(() -> storage.validate(file("trailing.png", trailing)), ErrorCode.FILE_MAGIC_INVALID);
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn((long) ScheduleImageStorage.MAX_BYTES + 1);
        assertCode(() -> storage.validate(oversized), ErrorCode.FILE_SIZE_EXCEEDED);
    }

    @Test
    void limitsConcurrentRetainedImagesAndReleasesAfterFailureOrClose() throws Exception {
        var storage = new ScheduleImageStorage(root.toString());
        var first = storage.validate(file("first.png", image("png")));
        var second = storage.validate(file("second.png", image("png")));
        try {
            assertCode(() -> storage.validate(file("third.png", new byte[]{1})), ErrorCode.SCHEDULE_IMAGE_BUSY);
            first.close(); first.close();
            assertCode(() -> storage.validate(file("bad.png", new byte[]{1})), ErrorCode.FILE_MAGIC_INVALID);
            try (var third = storage.validate(file("third.png", image("png")))) {
                assertThat(third.mediaType()).isEqualTo("image/png");
            }
            assertThatThrownBy(() -> storage.store(1, first)).isInstanceOf(IllegalStateException.class);
        } finally {
            first.close(); second.close();
        }
    }

    @Test
    void refusesSymlinkedDirectoriesAndUnsafeNamesWithoutTouchingOtherFiles() throws Exception {
        var storage = new ScheduleImageStorage(root.toString());
        Path outside = root.resolve("outside"); Files.createDirectory(outside);
        Files.createDirectories(root.resolve("raw"));
        Files.createSymbolicLink(root.resolve("raw/schedule-import"), outside);
        try (var validated = storage.validate(file("ok.png", image("png")))) {
            assertThatThrownBy(() -> storage.store(1, validated)).isInstanceOf(IllegalStateException.class);
        }
        try (var entries = Files.list(outside)) { assertThat(entries.count()).isZero(); }
        assertThatThrownBy(() -> storage.delete(1, "../foreign.png")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountCleanupDeletesOnlyItsOwnManagedImages() throws Exception {
        var storage = new ScheduleImageStorage(root.toString());
        String ownerName, otherName;
        try (var image = storage.validate(file("x.png", image("png")))) { ownerName = storage.store(1, image); }
        try (var image = storage.validate(file("x.png", image("png")))) { otherName = storage.store(2, image); }
        storage.deleteUserImages(1);
        storage.deleteUserImages(1);
        assertThatThrownBy(() -> storage.read(1, ownerName)).isInstanceOf(BusinessException.class);
        assertThat(storage.read(2, otherName)).isNotEmpty();
    }

    private static void assertCode(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode()).isEqualTo(code));
    }
    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("image", name, "application/octet-stream", bytes);
    }
    private static byte[] image(String format) throws Exception {
        var buffer = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), format, buffer)).isTrue();
        return buffer.toByteArray();
    }
    private static byte[] insertPngChunk(byte[] png, String type, byte[] data) throws Exception {
        var buffer = new ByteArrayOutputStream();
        buffer.write(png, 0, png.length - 12);
        buffer.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] chunkType = type.getBytes(StandardCharsets.US_ASCII);
        buffer.write(chunkType); buffer.write(data);
        CRC32 crc = new CRC32(); crc.update(chunkType); crc.update(data);
        buffer.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        buffer.write(png, png.length - 12, 12);
        return buffer.toByteArray();
    }
}
