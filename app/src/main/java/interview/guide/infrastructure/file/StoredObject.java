package interview.guide.infrastructure.file;

public record StoredObject(
    String objectKey,
    String mimeType,
    long fileSize) {
}
