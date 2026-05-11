package gr.tuc.distributed.manager.minio;

public class MinioOperationException extends RuntimeException {

    public MinioOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
