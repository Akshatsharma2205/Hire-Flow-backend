package com.kartgen.hireflow.document;

import com.kartgen.hireflow.config.HireFlowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Production S3 storage adapter. Works with AWS S3 and S3-compatible stores
 * (MinIO etc.) when hireflow.storage.s3-endpoint is set.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "hireflow.storage.provider", havingValue = "s3")
public class S3StorageAdapter implements StoragePort {

    private final HireFlowProperties props;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3StorageAdapter(HireFlowProperties props) {
        this.props = props;
        var storage = props.getStorage();
        if (storage.getS3Bucket() == null || storage.getS3Bucket().isBlank()) {
            throw new IllegalStateException("hireflow.storage.s3-bucket is required when provider=s3");
        }
        this.bucket = storage.getS3Bucket();

        var clientBuilder = S3Client.builder()
                .region(Region.of(storage.getS3Region()))
                .credentialsProvider(DefaultCredentialsProvider.create());

        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(storage.getS3Region()))
                .credentialsProvider(DefaultCredentialsProvider.create());

        if (storage.getS3Endpoint() != null && !storage.getS3Endpoint().isBlank()) {
            URI endpoint = URI.create(storage.getS3Endpoint());
            S3Configuration serviceConfig = S3Configuration.builder()
                    .pathStyleAccessEnabled(storage.isS3PathStyle())
                    .build();
            clientBuilder.endpointOverride(endpoint).serviceConfiguration(serviceConfig);
            presignerBuilder.endpointOverride(endpoint).serviceConfiguration(serviceConfig);
        }

        this.s3 = clientBuilder.build();
        this.presigner = presignerBuilder.build();
        log.info("S3 storage enabled bucket={} region={} endpoint={}",
                bucket, storage.getS3Region(), storage.getS3Endpoint());
    }

    @Override
    public String store(UUID orgId, String sha256, String mimeType, byte[] data) {
        String ext = mimeType != null && mimeType.contains("pdf") ? ".pdf" : ".docx";
        // Unique, never-overwritten object key per store call; see
        // LocalDiskStorageAdapter for the concurrency rationale.
        String objectKey = orgId + "/" + sha256 + "/" + UUID.randomUUID() + ext;
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(mimeType)
                            .build(),
                    RequestBody.fromBytes(data));
            return objectKey;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to store resume in S3: " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            // S3 delete is idempotent: deleting a missing object returns success,
            // so repeated compensation attempts are safe.
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
            log.debug("Delete requested for S3 object key={}", objectKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete resume from S3: " + objectKey, e);
        }
    }

    @Override
    public byte[] retrieve(String objectKey) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
            return bytes.asByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read resume from S3: " + objectKey, e);
        }
    }

    @Override
    public String generateViewUrl(String objectKey, String fileName) {
        int minutes = Math.max(1, props.getStorage().getS3PresignedExpiryMinutes());
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .responseContentDisposition("inline; filename=\"" + safeFileName(fileName) + "\"")
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(minutes))
                        .getObjectRequest(get)
                        .build());
        return presigned.url().toString();
    }

    @Override
    public String bucketName() {
        return bucket;
    }

    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "resume.pdf";
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
