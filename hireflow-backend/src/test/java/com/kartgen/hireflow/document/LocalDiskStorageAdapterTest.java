package com.kartgen.hireflow.document;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kartgen.hireflow.config.HireFlowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * J-2B adapter coverage for the local-disk implementation: unique-key
 * behaviour, delete semantics, idempotent/repeated delete, missing-object
 * handling and path-traversal protection. Uses a real temporary directory so
 * storage-state assertions inspect the actual filesystem.
 *
 * <p>J-2B.1 additionally covers the F-01 partial-write correction: stores are
 * staged to a uniquely named file and moved into place, so an I/O failure
 * after file creation can never expose a partial object at the final key.
 * Failure injection uses the adapter's protected seams (no mocks, no disk-full
 * tricks).</p>
 */
class LocalDiskStorageAdapterTest {

    @TempDir
    Path tempDir;

    private LocalDiskStorageAdapter adapter;

    private static HireFlowProperties propsFor(Path base) {
        HireFlowProperties props = new HireFlowProperties();
        props.getStorage().setLocalBasePath(base.toString());
        return props;
    }

    @BeforeEach
    void setUp() {
        adapter = new LocalDiskStorageAdapter(propsFor(tempDir));
    }

    @Test
    void storeAlwaysCreatesUniqueKeysAndNeverOverwrites() throws Exception {
        UUID orgId = UUID.randomUUID();
        String sha256 = "a".repeat(64);
        byte[] data = "resume-bytes-v1".getBytes();

        String key1 = adapter.store(orgId, sha256, "application/pdf", data);
        String key2 = adapter.store(orgId, sha256, "application/pdf", data);

        // Two stores with identical inputs must yield two distinct objects:
        // a shared deterministic key would let a failed transaction's rollback
        // compensation delete an object still referenced by a concurrent winner.
        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1).startsWith(orgId + "/" + sha256 + "/");
        assertThat(key2).startsWith(orgId + "/" + sha256 + "/");

        assertThat(Files.exists(tempDir.resolve(key1))).isTrue();
        assertThat(Files.exists(tempDir.resolve(key2))).isTrue();
        assertThat(Files.readAllBytes(tempDir.resolve(key1))).isEqualTo(data);
        assertThat(Files.readAllBytes(tempDir.resolve(key2))).isEqualTo(data);
    }

    @Test
    void deleteRemovesTheObjectAndIsIdempotentForMissingKeys() throws Exception {
        String key = adapter.store(UUID.randomUUID(), "b".repeat(64), "application/pdf", new byte[]{1, 2, 3});
        Path path = tempDir.resolve(key);

        assertThat(Files.exists(path)).isTrue();
        adapter.delete(key);
        assertThat(Files.exists(path)).isFalse();

        // Repeated delete of the same (now missing) key is a safe no-op —
        // required so explicit abandonment + rollback compensation can both
        // attempt the same delete without error.
        adapter.delete(key);
        adapter.delete(key);
    }

    @Test
    void deleteRejectsPathTraversalOutsideBase() {
        assertThatThrownBy(() -> adapter.delete("../../outside/secret.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid storage object key");
        assertThat(Files.exists(tempDir.resolve("outside"))).isFalse();
    }

    @Test
    void retrieveRejectsPathTraversalOutsideBase() {
        assertThatThrownBy(() -> adapter.retrieve("..\\..\\windows\\evil.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid storage object key");
    }

    @Test
    void storeAndRetrieveRoundTripPreservesBytes() {
        byte[] data = new byte[4096];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        String key = adapter.store(UUID.randomUUID(), "c".repeat(64), "application/msword", data);
        assertThat(adapter.retrieve(key)).isEqualTo(data);
        assertThat(adapter.bucketName()).isEqualTo("local");
    }

    // ── J-2B.1 F-01: partial-write failure injection ──

    @Test
    void partialWriteFailureLeavesNoFinalObjectAndCleansStaging() throws Exception {
        LocalDiskStorageAdapter failing = new FailingWriteAdapter(tempDir);
        UUID orgId = UUID.randomUUID();
        String sha256 = "d".repeat(64);
        byte[] data = "full resume bytes that are only partially written before the failure".getBytes();

        IllegalStateException ex = catchThrowableOfType(
                () -> failing.store(orgId, sha256, "application/pdf", data),
                IllegalStateException.class);

        // store() fails and the original I/O exception is preserved as the cause.
        assertThat(ex).hasMessageContaining("Failed to store file locally");
        assertThat(ex.getCause()).isInstanceOf(IOException.class)
                .hasMessageContaining("simulated partial write");

        // No partial final object is exposed under the org/sha prefix: the
        // directory was created but holds nothing (only a staging file would
        // have been written, and it was removed).
        Path prefix = tempDir.resolve(orgId.toString()).resolve(sha256);
        assertThat(Files.exists(prefix)).isTrue();
        try (var entries = Files.list(prefix)) {
            assertThat(entries).isEmpty();
        }
        // No temporary artifacts remain anywhere under the storage base.
        assertNoStagingArtifacts();
    }

    @Test
    void partialWriteFailureNeverDeletesPreExistingObjects() throws Exception {
        UUID orgId = UUID.randomUUID();
        byte[] existing = "pre-existing object bytes".getBytes();
        String existingKey = adapter.store(orgId, "e".repeat(64), "application/pdf", existing);

        LocalDiskStorageAdapter failing = new FailingWriteAdapter(tempDir);
        assertThatThrownBy(() -> failing.store(orgId, "f".repeat(64), "application/pdf",
                "other content".getBytes()))
                .isInstanceOf(IllegalStateException.class);

        // The pre-existing object is untouched, byte-identical, still retrievable.
        assertThat(adapter.retrieve(existingKey)).isEqualTo(existing);
        assertThat(Files.exists(tempDir.resolve(existingKey))).isTrue();
        assertNoStagingArtifacts();
    }

    @Test
    void cleanupFailureIsLoggedAndDoesNotMaskOriginalException() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger adapterLogger = (Logger) LoggerFactory.getLogger(LocalDiskStorageAdapter.class);
        adapterLogger.addAppender(appender);
        try {
            LocalDiskStorageAdapter failing = new FailingWriteAndCleanupAdapter(tempDir);
            UUID orgId = UUID.randomUUID();

            IllegalStateException ex = catchThrowableOfType(
                    () -> failing.store(orgId, "g".repeat(64), "application/pdf", "bytes".getBytes()),
                    IllegalStateException.class);

            // The original exception is not masked; the cleanup failure is
            // attached as suppressed and logged for reconciliation.
            assertThat(ex.getCause()).isInstanceOf(IOException.class)
                    .hasMessageContaining("simulated partial write");
            assertThat(ex.getCause().getSuppressed())
                    .extracting(Throwable::getMessage)
                    .contains("simulated cleanup failure");

            boolean logged = appender.list.stream().anyMatch(e ->
                    e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("Failed to remove staging file"));
            assertThat(logged).as("cleanup failure must be logged").isTrue();

            // Honest assertion: with cleanup failing, the staging artifact
            // remains (reconciliation gap), and no final object exists.
            try (var walk = Files.walk(tempDir)) {
                assertThat(walk.filter(p -> p.getFileName().toString().contains(".staging-")))
                        .hasSize(1);
            }
        } finally {
            adapterLogger.detachAppender(appender);
        }
    }

    private void assertNoStagingArtifacts() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            assertThat(walk.filter(p -> p.getFileName().toString().contains(".staging-")))
                    .as("no staging artifacts may remain")
                    .isEmpty();
        }
    }

    // ── J-2B.2 M-01: deterministic final-key collision ──

    @Test
    void existingFinalObjectCollisionFailsSafelyAndNeverOverwrites() throws Exception {
        UUID orgId = UUID.randomUUID();
        String sha256 = "h".repeat(64);
        String fixedKey = orgId + "/" + sha256 + "/fixed-collision.pdf";
        byte[] existingBytes = "pre-existing committed object bytes".getBytes();

        // Pre-create the final object exactly where this store attempt will
        // try to publish (deterministic collision — no UUID lottery).
        Files.createDirectories(tempDir.resolve(orgId.toString()).resolve(sha256));
        Files.write(tempDir.resolve(fixedKey), existingBytes);

        LocalDiskStorageAdapter colliding = new FixedKeyAdapter(tempDir, fixedKey);
        byte[] newBytes = "brand new content that must never replace the existing object".getBytes();

        IllegalStateException ex = catchThrowableOfType(
                () -> colliding.store(orgId, sha256, "application/pdf", newBytes),
                IllegalStateException.class);

        // The operation fails safely: the provider-level no-overwrite contract
        // (exclusive hard-link, or the no-option move fallback) rejected the
        // publication of the new object over the existing one.
        assertThat(ex.getCause()).isInstanceOf(IOException.class);

        // The existing final object's bytes are unchanged and the new content
        // was never published over it.
        assertThat(Files.readAllBytes(tempDir.resolve(fixedKey))).isEqualTo(existingBytes);
        assertThat(Files.readAllBytes(tempDir.resolve(fixedKey))).isNotEqualTo(newBytes);

        // Staging was cleaned up; nothing else was created or deleted.
        assertNoStagingArtifacts();
        try (var entries = Files.list(tempDir.resolve(orgId.toString()).resolve(sha256))) {
            assertThat(entries).containsExactly(tempDir.resolve(fixedKey));
        }
    }

    // ── J-2B.2 L-02: runtime-exception cleanup ──

    @Test
    void runtimeFailureAfterStagingCreationCleansUpAndStaysPrimary() throws Exception {
        LocalDiskStorageAdapter failing = new RuntimeFailureWriteAdapter(tempDir);
        UUID orgId = UUID.randomUUID();
        String sha256 = "i".repeat(64);

        // The original runtime exception is the primary failure (not wrapped,
        // not replaced by the cleanup).
        RuntimeException thrown = catchThrowableOfType(
                () -> failing.store(orgId, sha256, "application/pdf", "content".getBytes()),
                RuntimeException.class);
        assertThat(thrown).isInstanceOf(SecurityException.class)
                .hasMessageContaining("simulated runtime failure after staging creation");
        assertThat(thrown).isNotInstanceOf(IllegalStateException.class);

        // Staging cleanup ran despite the runtime failure; no final object
        // was exposed.
        assertNoStagingArtifacts();
        Path prefix = tempDir.resolve(orgId.toString()).resolve(sha256);
        try (var entries = Files.list(prefix)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void runtimeCleanupFailureIsSuppressedAndOriginalStaysPrimary() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger adapterLogger = (Logger) LoggerFactory.getLogger(LocalDiskStorageAdapter.class);
        adapterLogger.addAppender(appender);
        try {
            LocalDiskStorageAdapter failing = new RuntimeFailureCleanupAdapter(tempDir);
            UUID orgId = UUID.randomUUID();

            IllegalStateException ex = catchThrowableOfType(
                    () -> failing.store(orgId, "j".repeat(64), "application/pdf", "bytes".getBytes()),
                    IllegalStateException.class);

            // Original I/O failure stays primary; the runtime cleanup failure
            // is attached as suppressed, never masking it.
            assertThat(ex.getCause()).isInstanceOf(IOException.class)
                    .hasMessageContaining("simulated partial write failure");
            assertThat(ex.getCause().getSuppressed())
                    .extracting(Throwable::getMessage)
                    .contains("simulated runtime cleanup failure");

            boolean logged = appender.list.stream().anyMatch(e ->
                    e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("Failed to remove staging file"));
            assertThat(logged).as("cleanup failure must be logged").isTrue();

            // Honest: with cleanup failing, the staging artifact remains and no
            // final object exists.
            try (var walk = Files.walk(tempDir)) {
                assertThat(walk.filter(p -> p.getFileName().toString().contains(".staging-")))
                        .hasSize(1);
            }
        } finally {
            adapterLogger.detachAppender(appender);
        }
    }

    @Test
    void existingFinalObjectStaysByteIdenticalOnRuntimeFinalizationFailure() throws Exception {
        UUID orgId = UUID.randomUUID();
        String sha256 = "k".repeat(64);
        String fixedKey = orgId + "/" + sha256 + "/fixed-runtime.pdf";
        byte[] existingBytes = "existing final object that must survive a runtime failure".getBytes();
        Files.createDirectories(tempDir.resolve(orgId.toString()).resolve(sha256));
        Files.write(tempDir.resolve(fixedKey), existingBytes);

        LocalDiskStorageAdapter failing = new RuntimeFailureMoveAdapter(tempDir, fixedKey);

        RuntimeException thrown = catchThrowableOfType(
                () -> failing.store(orgId, sha256, "application/pdf", "new content".getBytes()),
                RuntimeException.class);
        assertThat(thrown).isInstanceOf(SecurityException.class)
                .hasMessageContaining("simulated runtime failure during finalization");

        // The pre-existing final object is byte-identical; the new object was
        // never published; staging was cleaned up.
        assertThat(Files.readAllBytes(tempDir.resolve(fixedKey))).isEqualTo(existingBytes);
        assertNoStagingArtifacts();
        try (var entries = Files.list(tempDir.resolve(orgId.toString()).resolve(sha256))) {
            assertThat(entries).containsExactly(tempDir.resolve(fixedKey));
        }
    }

    // ── J-2B.2 failure-injection seam subclasses ──

    /** Forces a deterministic final-key collision by fixing the object key. */
    private static final class FixedKeyAdapter extends LocalDiskStorageAdapter {
        private final String fixedKey;

        FixedKeyAdapter(Path base, String fixedKey) {
            super(propsFor(base));
            this.fixedKey = fixedKey;
        }

        @Override
        protected String buildObjectKey(UUID orgId, String sha256, String mimeType) {
            return fixedKey;
        }
    }

    /** Throws a runtime exception after fully creating the staging file. */
    private static final class RuntimeFailureWriteAdapter extends LocalDiskStorageAdapter {
        RuntimeFailureWriteAdapter(Path base) {
            super(propsFor(base));
        }

        @Override
        protected void writeStagedFile(Path stagingPath, byte[] data) throws IOException {
            Files.write(stagingPath, data, StandardOpenOption.CREATE_NEW);
            throw new SecurityException("simulated runtime failure after staging creation");
        }
    }

    /** I/O failure during the write, runtime failure during staging cleanup. */
    private static final class RuntimeFailureCleanupAdapter extends LocalDiskStorageAdapter {
        RuntimeFailureCleanupAdapter(Path base) {
            super(propsFor(base));
        }

        @Override
        protected void writeStagedFile(Path stagingPath, byte[] data) throws IOException {
            Files.write(stagingPath, data, StandardOpenOption.CREATE_NEW);
            throw new IOException("simulated partial write failure");
        }

        @Override
        protected void removeStagingFile(Path stagingPath) throws IOException {
            throw new SecurityException("simulated runtime cleanup failure");
        }
    }

    /** Runtime failure during finalization, with a fixed final key. */
    private static final class RuntimeFailureMoveAdapter extends LocalDiskStorageAdapter {
        private final String fixedKey;

        RuntimeFailureMoveAdapter(Path base, String fixedKey) {
            super(propsFor(base));
            this.fixedKey = fixedKey;
        }

        @Override
        protected String buildObjectKey(UUID orgId, String sha256, String mimeType) {
            return fixedKey;
        }

        @Override
        protected void moveIntoPlace(Path stagingPath, Path finalPath) throws IOException {
            throw new SecurityException("simulated runtime failure during finalization");
        }
    }

    /**
     * Fails the write after creating the staging file and writing half the
     * content — a deterministic partial-write failure at the adapter seam.
     */
    private static final class FailingWriteAdapter extends LocalDiskStorageAdapter {
        FailingWriteAdapter(Path base) {
            super(propsFor(base));
        }

        @Override
        protected void writeStagedFile(Path stagingPath, byte[] data) throws IOException {
            Files.write(stagingPath, Arrays.copyOf(data, Math.max(1, data.length / 2)),
                    StandardOpenOption.CREATE_NEW);
            throw new IOException("simulated partial write failure");
        }
    }

    /** Fails the write AND the subsequent staging-file cleanup. */
    private static final class FailingWriteAndCleanupAdapter extends LocalDiskStorageAdapter {
        FailingWriteAndCleanupAdapter(Path base) {
            super(propsFor(base));
        }

        @Override
        protected void writeStagedFile(Path stagingPath, byte[] data) throws IOException {
            Files.write(stagingPath, data, StandardOpenOption.CREATE_NEW);
            throw new IOException("simulated partial write failure");
        }

        @Override
        protected void removeStagingFile(Path stagingPath) throws IOException {
            throw new IOException("simulated cleanup failure");
        }
    }
}
