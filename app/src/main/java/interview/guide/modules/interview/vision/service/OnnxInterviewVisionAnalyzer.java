package interview.guide.modules.interview.vision.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.vision.model.FrameInput;
import interview.guide.modules.interview.vision.model.VisionAnalysisResult;
import interview.guide.modules.interview.vision.model.VisionEventType;
import jakarta.annotation.PreDestroy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnnxInterviewVisionAnalyzer implements VisionAnalyzerProvider {

  private static final int[] SCRFD_STRIDES = {8, 16, 32, 64, 128};
  private static final double[][] ARCFACE_TEMPLATE = {
      {38.2946, 51.6963},
      {73.5318, 51.5014},
      {56.0252, 71.7366},
      {41.5493, 92.3655},
      {70.7299, 92.2041}
  };

  private final InterviewVisionProperties properties;
  private final Map<String, IdentityReference> references = new ConcurrentHashMap<>();
  private final Object initializationLock = new Object();

  private volatile OrtEnvironment environment;
  private volatile OrtSession detectorSession;
  private volatile OrtSession recognitionSession;
  private volatile String detectorInputName;
  private volatile String recognitionInputName;

  @Override
  public String providerId() {
    return "onnx";
  }

  @Override
  public VisionAnalysisResult analyze(FrameInput frame) {
    boolean lowLight = frame.brightness() != null
        && frame.brightness() < properties.getLowLightThreshold();
    if (!frame.cameraActive()) {
      List<VisionEventType> events = new ArrayList<>();
      events.add(VisionEventType.CAMERA_INTERRUPTED);
      if (lowLight) events.add(VisionEventType.LOW_LIGHT);
      return new VisionAnalysisResult(false, 0, 0, lowLight, false, null, null, events);
    }

    ensureInitialized();
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame.frame()));
      if (image == null) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "无法解码抽帧图片");
      }
      List<FaceDetection> faces = detect(image);
      List<VisionEventType> events = objectiveEvents(faces, lowLight);
      IdentityDecision identity = verifyIdentity(frame, image, faces);
      if (Boolean.FALSE.equals(identity.samePerson())) {
        events.add(VisionEventType.IDENTITY_MISMATCH);
      }
      double confidence = faces.isEmpty() ? 0 : faces.getFirst().confidence();
      return new VisionAnalysisResult(
          !faces.isEmpty(),
          faces.size(),
          confidence,
          lowLight,
          true,
          identity.similarity(),
          identity.samePerson(),
          events);
    } catch (BusinessException e) {
      throw e;
    } catch (IOException | OrtException e) {
      log.error("ONNX vision inference failed", e);
      throw new BusinessException(
          ErrorCode.VISION_ANALYSIS_FAILED,
          "SCRFD/ArcFace ONNX 推理失败",
          e);
    }
  }

  private List<VisionEventType> objectiveEvents(
      List<FaceDetection> faces,
      boolean lowLight) {
    List<VisionEventType> events = new ArrayList<>();
    if (faces.isEmpty()) {
      events.add(VisionEventType.FACE_MISSING);
    } else if (faces.size() > 1) {
      events.add(VisionEventType.MULTIPLE_FACES);
    }
    if (lowLight) events.add(VisionEventType.LOW_LIGHT);
    return events;
  }

  private IdentityDecision verifyIdentity(
      FrameInput frame,
      BufferedImage image,
      List<FaceDetection> faces) throws OrtException {
    if (frame.sessionId() == null || faces.size() != 1) {
      return IdentityDecision.unknown();
    }
    FaceDetection face = faces.getFirst();
    if (face.confidence() < properties.getOnnx().getReferenceMinConfidence()) {
      return IdentityDecision.unknown();
    }
    evictExpiredReferences(frame.capturedAt());
    float[] embedding = recognize(image, face);
    IdentityReference reference = references.putIfAbsent(
        frame.sessionId(),
        new IdentityReference(embedding, frame.capturedAt()));
    if (reference == null) {
      log.info("ArcFace reference enrolled: sessionId={}", frame.sessionId());
      return new IdentityDecision(1D, true);
    }
    double similarity = cosine(reference.embedding(), embedding);
    return new IdentityDecision(
        similarity,
        similarity >= properties.getOnnx().getIdentityThreshold());
  }

  private void evictExpiredReferences(LocalDateTime now) {
    LocalDateTime cutoff = now.minus(properties.getOnnx().getReferenceTtl());
    references.entrySet().removeIf(entry -> entry.getValue().capturedAt().isBefore(cutoff));
  }

  private List<FaceDetection> detect(BufferedImage image) throws OrtException {
    int inputSize = properties.getOnnx().getDetectorInputSize();
    Letterbox letterbox = letterbox(image, inputSize);
    float[] tensorData = toDetectorTensor(letterbox.image(), inputSize);
    try (OnnxTensor tensor = OnnxTensor.createTensor(
        environment,
        FloatBuffer.wrap(tensorData),
        new long[] {1, 3, inputSize, inputSize});
        OrtSession.Result output = detectorSession.run(Map.of(detectorInputName, tensor))) {
      return decodeScrfd(output, letterbox.scale(), inputSize);
    }
  }

  private List<FaceDetection> decodeScrfd(
      OrtSession.Result output,
      double imageScale,
      int inputSize) throws OrtException {
    int outputCount = output.size();
    int landmarkFeatureMaps = outputCount % 3 == 0 ? outputCount / 3 : -1;
    boolean hasLandmarks = landmarkFeatureMaps >= 3
        && landmarkFeatureMaps <= SCRFD_STRIDES.length;
    int featureMapCount = hasLandmarks ? outputCount / 3 : outputCount / 2;
    if (featureMapCount < 3 || featureMapCount > SCRFD_STRIDES.length) {
      throw new BusinessException(
          ErrorCode.VISION_ANALYSIS_FAILED,
          "不支持的 SCRFD 输出数量: " + outputCount);
    }

    List<FaceDetection> candidates = new ArrayList<>();
    for (int level = 0; level < featureMapCount; level++) {
      float[][] scores = toMatrix(output.get(level), 1);
      float[][] boxes = toMatrix(output.get(level + featureMapCount), 4);
      float[][] landmarks = hasLandmarks
          ? toMatrix(output.get(level + featureMapCount * 2), 10)
          : null;
      int stride = SCRFD_STRIDES[level];
      int cells = (inputSize / stride) * (inputSize / stride);
      int anchors = Math.max(1, scores.length / cells);
      int rows = Math.min(scores.length, boxes.length);
      for (int row = 0; row < rows; row++) {
        double score = scores[row][0];
        if (score < properties.getOnnx().getDetectionThreshold()) continue;
        int cell = row / anchors;
        double centerX = (cell % (inputSize / stride)) * stride;
        double centerY = (cell / (inputSize / stride)) * stride;
        double left = (centerX - boxes[row][0] * stride) / imageScale;
        double top = (centerY - boxes[row][1] * stride) / imageScale;
        double right = (centerX + boxes[row][2] * stride) / imageScale;
        double bottom = (centerY + boxes[row][3] * stride) / imageScale;
        double[][] keypoints = decodeLandmarks(
            landmarks,
            row,
            centerX,
            centerY,
            stride,
            imageScale);
        candidates.add(new FaceDetection(left, top, right, bottom, score, keypoints));
      }
    }
    return nonMaximumSuppression(candidates, properties.getOnnx().getNmsThreshold());
  }

  private double[][] decodeLandmarks(
      float[][] landmarks,
      int row,
      double centerX,
      double centerY,
      int stride,
      double scale) {
    if (landmarks == null || row >= landmarks.length) return null;
    double[][] points = new double[5][2];
    for (int point = 0; point < points.length; point++) {
      points[point][0] = (centerX + landmarks[row][point * 2] * stride) / scale;
      points[point][1] = (centerY + landmarks[row][point * 2 + 1] * stride) / scale;
    }
    return points;
  }

  private List<FaceDetection> nonMaximumSuppression(
      List<FaceDetection> candidates,
      double threshold) {
    List<FaceDetection> sorted = candidates.stream()
        .sorted(Comparator.comparingDouble(FaceDetection::confidence).reversed())
        .toList();
    List<FaceDetection> kept = new ArrayList<>();
    for (FaceDetection candidate : sorted) {
      if (kept.stream().noneMatch(face -> intersectionOverUnion(face, candidate) > threshold)) {
        kept.add(candidate);
      }
    }
    return kept;
  }

  private double intersectionOverUnion(FaceDetection first, FaceDetection second) {
    double left = Math.max(first.left(), second.left());
    double top = Math.max(first.top(), second.top());
    double right = Math.min(first.right(), second.right());
    double bottom = Math.min(first.bottom(), second.bottom());
    double intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
    double union = first.area() + second.area() - intersection;
    return union <= 0 ? 0 : intersection / union;
  }

  private float[] recognize(BufferedImage image, FaceDetection face) throws OrtException {
    int inputSize = properties.getOnnx().getRecognitionInputSize();
    BufferedImage aligned = alignFace(image, face, inputSize);
    float[] tensorData = toRecognitionTensor(aligned, inputSize);
    try (OnnxTensor tensor = OnnxTensor.createTensor(
        environment,
        FloatBuffer.wrap(tensorData),
        new long[] {1, 3, inputSize, inputSize});
        OrtSession.Result output = recognitionSession.run(
            Map.of(recognitionInputName, tensor))) {
      float[] embedding = flatten(output.get(0).getValue());
      normalize(embedding);
      return embedding;
    }
  }

  private BufferedImage alignFace(BufferedImage source, FaceDetection face, int size) {
    if (face.landmarks() == null) return cropFace(source, face, size);
    double[][] target = new double[5][2];
    double targetScale = size / 112D;
    for (int index = 0; index < target.length; index++) {
      target[index][0] = ARCFACE_TEMPLATE[index][0] * targetScale;
      target[index][1] = ARCFACE_TEMPLATE[index][1] * targetScale;
    }
    double[] transform = solveSimilarity(face.landmarks(), target);
    if (transform == null) return cropFace(source, face, size);
    BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    double a = transform[0];
    double b = transform[1];
    double tx = transform[2];
    double ty = transform[3];
    double determinant = a * a + b * b;
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        double sourceX = (a * (x - tx) + b * (y - ty)) / determinant;
        double sourceY = (-b * (x - tx) + a * (y - ty)) / determinant;
        result.setRGB(x, y, bilinearRgb(source, sourceX, sourceY));
      }
    }
    return result;
  }

  private double[] solveSimilarity(double[][] source, double[][] target) {
    double[][] normal = new double[4][5];
    for (int index = 0; index < source.length; index++) {
      accumulateNormal(normal, new double[] {source[index][0], -source[index][1], 1, 0},
          target[index][0]);
      accumulateNormal(normal, new double[] {source[index][1], source[index][0], 0, 1},
          target[index][1]);
    }
    return solve(normal);
  }

  private void accumulateNormal(double[][] normal, double[] row, double value) {
    for (int i = 0; i < 4; i++) {
      for (int j = 0; j < 4; j++) normal[i][j] += row[i] * row[j];
      normal[i][4] += row[i] * value;
    }
  }

  private double[] solve(double[][] matrix) {
    for (int pivot = 0; pivot < 4; pivot++) {
      int best = pivot;
      for (int row = pivot + 1; row < 4; row++) {
        if (Math.abs(matrix[row][pivot]) > Math.abs(matrix[best][pivot])) best = row;
      }
      if (Math.abs(matrix[best][pivot]) < 1e-9) return null;
      double[] swap = matrix[pivot];
      matrix[pivot] = matrix[best];
      matrix[best] = swap;
      double divisor = matrix[pivot][pivot];
      for (int column = pivot; column <= 4; column++) matrix[pivot][column] /= divisor;
      for (int row = 0; row < 4; row++) {
        if (row == pivot) continue;
        double factor = matrix[row][pivot];
        for (int column = pivot; column <= 4; column++) {
          matrix[row][column] -= factor * matrix[pivot][column];
        }
      }
    }
    return new double[] {matrix[0][4], matrix[1][4], matrix[2][4], matrix[3][4]};
  }

  private BufferedImage cropFace(BufferedImage source, FaceDetection face, int size) {
    double side = Math.max(face.right() - face.left(), face.bottom() - face.top()) * 1.25;
    double centerX = (face.left() + face.right()) / 2;
    double centerY = (face.top() + face.bottom()) / 2;
    BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        double sourceX = centerX - side / 2 + x * side / size;
        double sourceY = centerY - side / 2 + y * side / size;
        result.setRGB(x, y, bilinearRgb(source, sourceX, sourceY));
      }
    }
    return result;
  }

  private int bilinearRgb(BufferedImage image, double x, double y) {
    if (x < 0 || y < 0 || x >= image.getWidth() - 1 || y >= image.getHeight() - 1) {
      return 0;
    }
    int x0 = (int) Math.floor(x);
    int y0 = (int) Math.floor(y);
    double dx = x - x0;
    double dy = y - y0;
    int[] rgb = {
        image.getRGB(x0, y0),
        image.getRGB(x0 + 1, y0),
        image.getRGB(x0, y0 + 1),
        image.getRGB(x0 + 1, y0 + 1)
    };
    int result = 0xff000000;
    for (int shift : new int[] {16, 8, 0}) {
      double top = ((rgb[0] >> shift) & 0xff) * (1 - dx)
          + ((rgb[1] >> shift) & 0xff) * dx;
      double bottom = ((rgb[2] >> shift) & 0xff) * (1 - dx)
          + ((rgb[3] >> shift) & 0xff) * dx;
      result |= ((int) Math.round(top * (1 - dy) + bottom * dy)) << shift;
    }
    return result;
  }

  private Letterbox letterbox(BufferedImage source, int size) {
    double scale = Math.min((double) size / source.getWidth(), (double) size / source.getHeight());
    int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
    int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
    BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        double sourceX = x / scale;
        double sourceY = y / scale;
        result.setRGB(x, y, bilinearRgb(source, sourceX, sourceY));
      }
    }
    return new Letterbox(result, scale);
  }

  private float[] toDetectorTensor(BufferedImage image, int size) {
    float[] tensor = new float[3 * size * size];
    for (int y = 0; y < size; y++) {
      for (int x = 0; x < size; x++) {
        int rgb = image.getRGB(x, y);
        int offset = y * size + x;
        tensor[offset] = (((rgb >> 16) & 0xff) - 127.5F) / 128F;
        tensor[size * size + offset] = (((rgb >> 8) & 0xff) - 127.5F) / 128F;
        tensor[size * size * 2 + offset] = ((rgb & 0xff) - 127.5F) / 128F;
      }
    }
    return tensor;
  }

  private float[] toRecognitionTensor(BufferedImage image, int size) {
    return toDetectorTensor(image, size);
  }

  private float[][] toMatrix(OnnxValue value, int columns) throws OrtException {
    TensorInfo info = (TensorInfo) value.getInfo();
    long elements = info.getNumElements();
    if (elements <= 0 || elements % columns != 0 || elements > Integer.MAX_VALUE) {
      throw new BusinessException(
          ErrorCode.VISION_ANALYSIS_FAILED,
          "SCRFD 输出形状不兼容: " + java.util.Arrays.toString(info.getShape()));
    }
    float[] flat = flatten(value.getValue());
    float[][] result = new float[flat.length / columns][columns];
    for (int row = 0; row < result.length; row++) {
      System.arraycopy(flat, row * columns, result[row], 0, columns);
    }
    return result;
  }

  private float[] flatten(Object array) {
    List<Float> values = new ArrayList<>();
    flattenInto(array, values);
    float[] result = new float[values.size()];
    for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
    return result;
  }

  private void flattenInto(Object value, List<Float> target) {
    if (value instanceof Number number) {
      target.add(number.floatValue());
      return;
    }
    int length = Array.getLength(value);
    for (int index = 0; index < length; index++) flattenInto(Array.get(value, index), target);
  }

  private void normalize(float[] vector) {
    double sum = 0;
    for (float value : vector) sum += value * value;
    double norm = Math.sqrt(sum);
    if (norm == 0) return;
    for (int index = 0; index < vector.length; index++) vector[index] /= (float) norm;
  }

  private double cosine(float[] first, float[] second) {
    if (first.length != second.length) return -1;
    double sum = 0;
    for (int index = 0; index < first.length; index++) sum += first[index] * second[index];
    return sum;
  }

  private void ensureInitialized() {
    if (detectorSession != null && recognitionSession != null) return;
    synchronized (initializationLock) {
      if (detectorSession != null && recognitionSession != null) return;
      Path detectorPath = resolveModelPath(properties.getOnnx().getDetectorModel());
      Path recognitionPath = resolveModelPath(properties.getOnnx().getRecognitionModel());
      try {
        environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        detectorSession = environment.createSession(detectorPath.toString(), options);
        recognitionSession = environment.createSession(recognitionPath.toString(), options);
        detectorInputName = detectorSession.getInputNames().iterator().next();
        recognitionInputName = recognitionSession.getInputNames().iterator().next();
        log.info(
            "ONNX vision models loaded: detector={}, recognition={}",
            detectorPath,
            recognitionPath);
      } catch (OrtException e) {
        throw new BusinessException(ErrorCode.VISION_ANALYSIS_FAILED, "ONNX 模型加载失败", e);
      }
    }
  }

  private Path resolveModelPath(String configuredPath) {
    Path path = Path.of(configuredPath);
    if (path.isAbsolute() && Files.isRegularFile(path)) return path.normalize();
    Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    Path direct = workingDirectory.resolve(path).normalize();
    if (Files.isRegularFile(direct)) return direct;
    Path parent = workingDirectory.getParent();
    if (parent != null) {
      Path fromParent = parent.resolve(path).normalize();
      if (Files.isRegularFile(fromParent)) return fromParent;
    }
    throw new BusinessException(
        ErrorCode.VISION_ANALYSIS_FAILED,
        "ONNX 模型不存在: " + configuredPath);
  }

  @PreDestroy
  void close() {
    closeSession(detectorSession);
    closeSession(recognitionSession);
  }

  private void closeSession(OrtSession session) {
    if (session == null) return;
    try {
      session.close();
    } catch (OrtException e) {
      log.warn("Failed to close ONNX session", e);
    }
  }

  private record FaceDetection(
      double left,
      double top,
      double right,
      double bottom,
      double confidence,
      double[][] landmarks) {

    double area() {
      return Math.max(0, right - left) * Math.max(0, bottom - top);
    }
  }

  private record Letterbox(BufferedImage image, double scale) {
  }

  private record IdentityReference(float[] embedding, LocalDateTime capturedAt) {
  }

  private record IdentityDecision(Double similarity, Boolean samePerson) {
    static IdentityDecision unknown() {
      return new IdentityDecision(null, null);
    }
  }
}
