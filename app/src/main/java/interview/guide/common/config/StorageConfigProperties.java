package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RustFS (S3兼容) 存储配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageConfigProperties {

    private String provider = "local";
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private String region = "us-east-1";
    private boolean pathStyleAccess = true;
    private boolean directAccessEnabled = false;
    private String localDir = System.getProperty("java.io.tmpdir") + "/interview-guide/storage";
}
