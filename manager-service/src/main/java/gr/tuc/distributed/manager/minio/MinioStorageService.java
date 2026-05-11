package gr.tuc.distributed.manager.minio;

import io.minio.*;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;

    @Value("${minio.bucket}")
    private String bucket;

    public MinioStorageService(MinioClient minioClient,
                               @Qualifier("publicMinioClient") MinioClient publicMinioClient) {
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
    }

    // Upload an InputStream to MinIO and return the object key
    public String upload(String objectKey, InputStream data, long size, String contentType) {
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(data, size, -1)
                    .contentType(contentType)
                    .build());
            log.info("Uploaded object: {}/{}", bucket, objectKey);
            return objectKey;
        } catch (Exception e) {
            throw new MinioOperationException("Failed to upload " + objectKey, e);
        }
    }

    // Download an object as an InputStream. Caller must close the stream
    public InputStream download(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            throw new MinioOperationException("Failed to download " + objectKey, e);
        }
    }

    // List all object keys under a given prefix
    public List<String> listObjects(String prefix) {
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .recursive(true)
                            .build());

            List<String> keys = new ArrayList<>();
            for (Result<Item> result : results) {
                keys.add(result.get().objectName());
            }
            return keys;
        } catch (Exception e) {
            throw new MinioOperationException("Failed to list objects under " + prefix, e);
        }
    }

    /** Generate a presigned GET URL valid for the given number of seconds.
     *  Uses the public-facing MinioClient so the signed host matches what
     *  the browser sends. */
    public String presignedGetUrl(String objectKey, int expirySeconds) {
        try {
            return publicMinioClient.getPresignedObjectUrl(
                    io.minio.GetPresignedObjectUrlArgs.builder()
                            .method(io.minio.http.Method.GET)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(expirySeconds)
                            .build());
        } catch (Exception e) {
            throw new MinioOperationException("Failed to generate presigned URL for " + objectKey, e);
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean found = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build());
        if (!found) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("Created bucket: {}", bucket);
        }
    }
}
