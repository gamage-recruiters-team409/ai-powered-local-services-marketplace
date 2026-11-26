package com.gamagerecruiters.marketplace.modules.customer.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.gamagerecruiters.marketplace.common.exception.ApiException;

@ExtendWith(MockitoExtension.class)
class CloudinaryCustomerProfileImageStorageTest {

    @Mock
    private Cloudinary cloudinary;

    @Mock
    private Uploader uploader;

    private CloudinaryCustomerProfileImageStorage storage;

    // JPEG header: FF D8 FF E0 ...
    private static final byte[] VALID_JPEG_BYTES = new byte[] {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46
    };

    // PNG header: 89 50 4E 47 0D 0A 1A 0A ...
    private static final byte[] VALID_PNG_BYTES = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D
    };

    @BeforeEach
    void setUp() {
        storage = new CloudinaryCustomerProfileImageStorage(cloudinary);
    }

    @Test
    @DisplayName("upload: successfully uploads valid JPEG image to Cloudinary")
    @SuppressWarnings("unchecked")
    void upload_validJpeg_success() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of(
                "public_id", "localserve/customer-profile/cust-1/test-uuid",
                "secure_url", "https://res.cloudinary.com/demo/image/upload/v1/test.jpg"
        ));

        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", VALID_JPEG_BYTES
        );

        StoredProfileImage result = storage.upload("cust-1", file);

        assertNotNull(result);
        assertEquals("localserve/customer-profile/cust-1/test-uuid", result.publicId());
        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/test.jpg", result.secureUrl());

        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(eq(VALID_JPEG_BYTES), optionsCaptor.capture());
        Map<String, Object> options = optionsCaptor.getValue();
        assertEquals("image", options.get("resource_type"));
        assertEquals("upload", options.get("type"));
        assertEquals(false, options.get("overwrite"));
        assertEquals("localserve/customer-profile/cust-1", options.get("folder"));
        assertNotNull(options.get("public_id"));
        assertDoesNotThrow(() -> UUID.fromString(String.valueOf(options.get("public_id"))));
    }

    @Test
    @DisplayName("upload: successfully uploads valid PNG image to Cloudinary")
    @SuppressWarnings("unchecked")
    void upload_validPng_success() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of(
                "public_id", "localserve/customer-profile/cust-1/test-uuid-png",
                "secure_url", "https://res.cloudinary.com/demo/image/upload/v1/test.png"
        ));

        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", VALID_PNG_BYTES
        );

        StoredProfileImage result = storage.upload("cust-1", file);

        assertNotNull(result);
        assertEquals("localserve/customer-profile/cust-1/test-uuid-png", result.publicId());
        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/test.png", result.secureUrl());
    }

    @Test
    @DisplayName("upload: sanitizes unsafe customerId replacing path traversal dots, slashes, spaces and symbols with underscores")
    @SuppressWarnings("unchecked")
    void upload_unsafeCustomerId_sanitizesPathAndDoesNotEscapeBaseFolder() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of(
                "public_id", "test-public-id",
                "secure_url", "https://res.cloudinary.com/demo/image/upload/v1/test.jpg"
        ));

        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", VALID_JPEG_BYTES
        );

        // Input contains traversal "../", nested path segments, spaces, and special symbols
        String unsafeId = "  ../../evil user/nested#dir@1!  ";
        storage.upload(unsafeId, file);

        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(eq(VALID_JPEG_BYTES), optionsCaptor.capture());
        Map<String, Object> options = optionsCaptor.getValue();

        String folder = (String) options.get("folder");
        // sanitizePathSegment trims and replaces [^A-Za-z0-9_-] with "_"
        // "../../evil user/nested#dir@1!" -> "______evil_user_nested_dir_1_"
        assertEquals("localserve/customer-profile/______evil_user_nested_dir_1_", folder);
        assertTrue(folder.startsWith("localserve/customer-profile/"));
        assertFalse(folder.contains(".."));
        assertFalse(folder.substring("localserve/customer-profile/".length()).contains("/"));
        assertDoesNotThrow(() -> UUID.fromString(String.valueOf(options.get("public_id"))));
    }

    @Test
    @DisplayName("upload: rejects null customerId with 400 INVALID_CUSTOMER_ID")
    void upload_nullCustomerId_throws400() {
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", VALID_JPEG_BYTES);
        ApiException ex = assertThrows(ApiException.class, () -> storage.upload(null, file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_CUSTOMER_ID", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: rejects blank customerId with 400 INVALID_CUSTOMER_ID")
    void upload_blankCustomerId_throws400() {
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", VALID_JPEG_BYTES);
        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("   ", file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_CUSTOMER_ID", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: rejects null file with 400 EMPTY_FILE")
    void upload_nullFile_throws400() {
        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("EMPTY_FILE", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: rejects empty file with 400 EMPTY_FILE")
    void upload_emptyFile_throws400() {
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0]);
        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("EMPTY_FILE", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: rejects file exceeding 5 MB with 400 FILE_TOO_LARGE")
    void upload_fileTooLarge_throws400() {
        // MockMultipartFile size is byte array length
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "large.jpg", "image/jpeg", oversized);
        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("FILE_TOO_LARGE", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: rejects 6 MB file with 400 FILE_TOO_LARGE (below 10MB servlet limit, above 5MB customer limit)")
    void upload_sixMegabytesFile_throws400FileTooLarge() {
        MultipartFile sixMbFile = org.mockito.Mockito.mock(MultipartFile.class);
        when(sixMbFile.isEmpty()).thenReturn(false);
        when(sixMbFile.getSize()).thenReturn(6L * 1024L * 1024L);

        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", sixMbFile));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("FILE_TOO_LARGE", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: rejects webp content type with 400 INVALID_FILE_TYPE")
    void upload_webp_throws400() {
        MockMultipartFile file = new MockMultipartFile("file", "a.webp", "image/webp", new byte[] { 1, 2, 3 });
        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_FILE_TYPE", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: rejects gif content type with 400 INVALID_FILE_TYPE")
    void upload_gif_throws400() {
        MockMultipartFile file = new MockMultipartFile("file", "a.gif", "image/gif", new byte[] { 1, 2, 3 });
        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_FILE_TYPE", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: rejects declared JPEG with wrong magic bytes with 400 INVALID_FILE_SIGNATURE")
    void upload_fakeJpegMagicBytes_throws400() {
        // Plain text pretending to be JPEG
        byte[] fakeBytes = "Hello World I am not JPEG".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "fake.jpg", "image/jpeg", fakeBytes);
        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_FILE_SIGNATURE", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: rejects declared PNG with wrong magic bytes with 400 INVALID_FILE_SIGNATURE")
    void upload_fakePngMagicBytes_throws400() {
        // JPEG bytes pretending to be PNG
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", VALID_JPEG_BYTES);
        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", file));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_FILE_SIGNATURE", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: handles IOException on getBytes with 400 UNREADABLE_FILE")
    void upload_ioExceptionOnGetBytes_throws400() throws Exception {
        MultipartFile failingFile = org.mockito.Mockito.mock(MultipartFile.class);
        when(failingFile.isEmpty()).thenReturn(false);
        when(failingFile.getSize()).thenReturn(100L);
        when(failingFile.getContentType()).thenReturn("image/jpeg");
        when(failingFile.getBytes()).thenThrow(new IOException("Disk read error"));

        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", failingFile));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("UNREADABLE_FILE", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: throws 502 STORAGE_ERROR when Cloudinary returns incomplete response")
    @SuppressWarnings("unchecked")
    void upload_incompleteCloudinaryResponse_throws502() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        // Missing secure_url
        when(uploader.upload(any(byte[].class), anyMap())).thenReturn(Map.of(
                "public_id", "some-id"
        ));

        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", VALID_JPEG_BYTES);

        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", file));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("STORAGE_ERROR", ex.getErrorCode());
    }

    @Test
    @DisplayName("upload: throws 502 STORAGE_ERROR when Cloudinary throws exception")
    @SuppressWarnings("unchecked")
    void upload_cloudinaryThrowsException_throws502() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap())).thenThrow(new RuntimeException("Cloudinary network timeout"));

        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", VALID_JPEG_BYTES);

        ApiException ex = assertThrows(ApiException.class, () -> storage.upload("cust-1", file));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("STORAGE_ERROR", ex.getErrorCode());
    }

    @Test
    @DisplayName("delete: returns true when publicId is null or blank")
    void delete_nullOrBlankPublicId_noop() {
        assertTrue(storage.delete(null));
        assertTrue(storage.delete("   "));
        verify(cloudinary, never()).uploader();
    }

    @Test
    @DisplayName("delete: calls cloudinary destroy with invalidate=true and returns true on 'ok'")
    @SuppressWarnings("unchecked")
    void delete_validPublicId_callsDestroy() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("some-public-id"), anyMap())).thenReturn(Map.of("result", "ok"));

        boolean result = storage.delete("some-public-id");
        assertTrue(result);

        ArgumentCaptor<Map<String, Object>> optionsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).destroy(eq("some-public-id"), optionsCaptor.capture());
        Map<String, Object> options = optionsCaptor.getValue();
        assertEquals("image", options.get("resource_type"));
        assertEquals("upload", options.get("type"));
        assertEquals(true, options.get("invalidate"));
    }

    @Test
    @DisplayName("delete: returns true when Cloudinary result is 'not found'")
    @SuppressWarnings("unchecked")
    void delete_notFoundResult_returnsTrue() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("missing-id"), anyMap())).thenReturn(Map.of("result", "not found"));

        boolean result = storage.delete("missing-id");
        assertTrue(result);
    }

    @Test
    @DisplayName("delete: returns false when Cloudinary result is not ok/not found")
    @SuppressWarnings("unchecked")
    void delete_errorResult_returnsFalse() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("error-id"), anyMap())).thenReturn(Map.of("result", "error"));

        boolean result = storage.delete("error-id");
        assertFalse(result);
    }

    @Test
    @DisplayName("delete: catches exception, logs warning and returns false without throwing")
    @SuppressWarnings("unchecked")
    void delete_destroyThrows_returnsFalse() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("failing-id"), anyMap())).thenThrow(new RuntimeException("Cloudinary destroy error"));

        boolean result = storage.delete("failing-id");
        assertFalse(result);
    }
}
