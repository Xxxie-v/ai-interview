package interview.guide.modules.interview.video.model;

public record VideoUploadCompleteResponse(
    String sessionId,
    int chunkCount,
    long totalSize,
    long totalDurationMs,
    boolean complete) {
}
