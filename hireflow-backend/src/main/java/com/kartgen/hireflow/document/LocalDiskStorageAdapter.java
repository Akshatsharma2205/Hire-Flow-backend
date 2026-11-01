package com.kartgen.hireflow.document;

import com.kartgen.hireflow.common.HashUtil;
import com.kartgen.hireflow.config.HireFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.UUID;

/**
 * Local-disk storage adapter used by the local profile. Serves files through
 * HMAC-signed URLs so the built-in console can preview PDFs without AWS.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hireflow.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalDiskStorageAdapter implements StoragePort {

    private final HireFlowProperties props;

    @Override
    public String store(UUID orgId, String sha256, String mimeType, byte[] data) {
        // Unique, never-overwritten object key per store call: the (org, sha256)
        // pair alone is NOT a safe key because a compensating delete registered
        // by a failed transaction could otherwise destroy an object still
        // referenced by a concurrent winner. The uuid segment makes every object
        // uniquely owned by exactly one transaction/row.
        String objectKey = buildObjectKey(orgId, sha256, mimeType);
        Path base = Path.of(props.getStorage().getLocalBasePath()).toAbsolutePath().normalize();
        Path finalPath = base.resolve(objectKey).normalize();
        if (!finalPath.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage object key");
        }
        // The full content is written to a uniquely named staging file in the
        // same directory, then published at the final key. A partial write can
        // therefore never expose a partial object at the final key, and a
        // pre-existing object at the final key is never overwritten (see
        // moveIntoPlace for the no-overwrite contract) and never deleted
        // (cleanup only ever targets the staging file this attempt created).
        Path stagingPath = base.resolve(objectKey + ".staging-" + UUID.randomUUID()).normalize();
        if (!stagingPath.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage object key");
        }
        try {
            Files.createDirectories(finalPath.getParent());
            writeStagedFile(stagingPath, data);
            moveIntoPlace(stagingPath, finalPath);
            log.debug("Stored file locally: {}", finalPath);
        } catch (IOException e) {
            cleanupStagingFile(stagingPath, e);
            throw new IllegalStateException("Failed to store file locally: " + finalPath, e);
        } catch (RuntimeException e) {
            // Ordinary runtime failures (e.g. SecurityException) after staging
            // creation must also trigger best-effort staging cleanup, while
            // remaining the primary exception (never wrapped, never masked).
            cleanupStagingFile(stagingPath, e);
            throw e;
        }
        return objectKey;
    }

    /**
     * Builds the unique, never-overwritten object key for a store call.
     * Protected failure-injection seam: tests override this to force a
     * deterministic final-key collision without relying on UUID randomness.
     */
    protected String buildObjectKey(UUID orgId, String sha256, String mimeType) {
        String ext = mimeType != null && mimeType.contains("pdf") ? ".pdf" : ".docx";
        return orgId + "/" + sha256 + "/" + UUID.randomUUID() + ext;
    }

    /**
     * Writes the complete staged content to the staging file.
     *
     * <p>Protected failure-injection seam: tests override this to simulate an
     * I/O failure after file creation and partial writing. The default
     * implementation creates the staging file fresh (CREATE_NEW) and writes
     * all bytes; closing the channel flushes the content before the move.</p>
     */
    protected void writeStagedFile(Path stagingPath, byte[] data) throws IOException {
        Files.write(stagingPath, data, StandardOpenOption.CREATE_NEW);
    }

    /**
     * Publishes the completed staging file at its final unique key with a
     * documented no-overwrite guarantee.
     *
     * <p><b>Primary strategy — atomic exclusive link.</b>
     * {@link Files#createLink} creates the final directory entry with a single
     * {@code link(2)} / {@code CreateHardLink} call, which is atomic and has
     * <em>no</em> replace semantics on POSIX or NTFS: if the final key already
     * exists the call fails (FileAlreadyExistsException) and the existing
     * object is never touched. The final name therefore appears atomically
     * with its complete content, and only once. The staging name is then
     * removed; if that removal fails the final object is still complete and
     * published, and the leftover staging name is logged as a reconcilable
     * artifact (never a partial object).</p>
     *
     * <p><b>Fallback — no-option move.</b> On filesystems without hard-link
     * support (some network/FAT mounts), {@link Files#move} with no options is
     * used. The Java NIO contract for a no-option move is explicit: "by
     * default, if the target file exists then it fails with
     * FileAlreadyExistsException" — so no-overwrite is guaranteed at the API
     * level without any application-level existence check (no TOCTOU in this
     * code). Documented trade-off: publication is an atomic same-directory
     * rename on both providers, but on POSIX providers the no-replace decision
     * is implemented inside the JDK as an existence pre-check before
     * {@code rename(2)}, so the no-overwrite check and the publication are not
     * a single syscall there. The final key embeds a random UUID, making a
     * same-key collision unreachable in practice; the fallback still fails
     * safely (FileAlreadyExistsException, never replacement) under the
     * documented contract.</p>
     */
    protected void moveIntoPlace(Path stagingPath, Path finalPath) throws IOException {
        try {
            Files.createLink(finalPath, stagingPath);
        } catch (FileAlreadyExistsException e) {
            // Deterministic collision: the existing final object must never be
            // replaced. Propagate immediately — do not fall through to move.
            throw e;
        } catch (UnsupportedOperationException | FileSystemException e) {
            Files.move(stagingPath, finalPath); // no options: FileAlreadyExistsException on collision
            return;
        }
        try {
            Files.delete(stagingPath);
        } catch (IOException e) {
            // The final object is complete and published; a leftover staging
            // name is a reconcilable artifact, not a failure of the store.
            log.warn("Final object published but staging name could not be removed: {}; error: {}",
                    stagingPath, e.getMessage());
        }
    }

    /**
     * Best-effort removal of the staging file after a failed store. Handles
     * both I/O and ordinary runtime failures (e.g. SecurityException); a
     * cleanup failure is logged (the staging path plus the original error, no
     * candidate data) and is attached as a suppressed exception so the
     * original failure is never masked. {@code Error} conditions are never
     * caught here.
     */
    private void cleanupStagingFile(Path stagingPath, Exception original) {
        try {
            removeStagingFile(stagingPath);
        } catch (Exception cleanup) {
            log.error("Failed to remove staging file after store failure: {}; original error: {}",
                    stagingPath, original.getMessage());
            original.addSuppressed(cleanup);
        }
    }

    /**
     * Removes the staging file, tolerating absence (idempotent).
     * Protected failure-injection seam for cleanup-failure tests.
     */
    protected void removeStagingFile(Path stagingPath) throws IOException {
        Files.deleteIfExists(stagingPath);
    }

    @Override
    public void delete(String objectKey) {
        Path base = Path.of(props.getStorage().getLocalBasePath()).toAbsolutePath().normalize();
        Path path = base.resolve(objectKey).normalize();
        if (!path.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage object key");
        }
        try {
            boolean deleted = Files.deleteIfExists(path);
            log.debug("Delete requested for local object key={} deleted={}", objectKey, deleted);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete file: " + path, e);
        }
    }

    @Override
    public byte[] retrieve(String objectKey) {
        Path base = Path.of(props.getStorage().getLocalBasePath()).toAbsolutePath().normalize();
        Path path = base.resolve(objectKey).normalize();
        if (!path.startsWith(base)) {
            throw new IllegalArgumentException("Invalid storage object key");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file: " + path, e);
        }
    }

    @Override
    public String generateViewUrl(String objectKey, String fileName) {
        long timestamp = System.currentTimeMillis() / 1000 + 3600;
        String payload = objectKey + ":" + timestamp;
        String hmac = HashUtil.hmacSha256(payload, props.getStorage().getLocalUrlSecret());
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + hmac;
        return "/api/v1/local-s3/" + token;
    }

    @Override
    public String bucketName() {
        return "local";
    }
}
