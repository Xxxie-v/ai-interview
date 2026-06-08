package interview.guide.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 兼容对象存储客户端配置。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")
public class S3Config {

  private final StorageConfigProperties storageConfig;

  @Bean
  public S3Client s3Client() {
    AwsBasicCredentials credentials = credentials();

    return S3Client.builder()
        .endpointOverride(URI.create(storageConfig.getEndpoint()))
        .region(Region.of(storageConfig.getRegion()))
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .serviceConfiguration(serviceConfiguration())
        .build();
  }

  @Bean
  public S3Presigner s3Presigner() {
    return S3Presigner.builder()
        .endpointOverride(URI.create(storageConfig.getEndpoint()))
        .region(Region.of(storageConfig.getRegion()))
        .credentialsProvider(StaticCredentialsProvider.create(credentials()))
        .serviceConfiguration(serviceConfiguration())
        .build();
  }

  private AwsBasicCredentials credentials() {
    return AwsBasicCredentials.create(
        storageConfig.getAccessKey(),
        storageConfig.getSecretKey());
  }

  private S3Configuration serviceConfiguration() {
    return S3Configuration.builder()
        .pathStyleAccessEnabled(storageConfig.isPathStyleAccess())
        // Alibaba Cloud OSS S3-compatible endpoints do not support aws-chunked encoding.
        .chunkedEncodingEnabled(false)
        .build();
  }
}
