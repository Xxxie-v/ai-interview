package interview.guide.modules.auth.sms;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dypnsapi.model.v20170525.SendSmsVerifyCodeRequest;
import com.aliyuncs.dypnsapi.model.v20170525.SendSmsVerifyCodeResponse;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.profile.DefaultProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.config.SmsProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AliyunPnvsSmsSender implements SmsService {

  private static final String SUCCESS_CODE = "OK";

  private final SmsProperties smsProperties;
  private final ObjectMapper objectMapper;

  @Override
  public void sendCode(String mobile, String code) {
    ensureConfigured();
    SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest();
    request.setPhoneNumber(mobile);
    request.setSignName(smsProperties.getSignName());
    request.setTemplateCode(smsProperties.getTemplateCode());
    request.setTemplateParam(templateParameters(code));
    request.setSchemeName(smsProperties.getSchemeName());
    request.setCountryCode(smsProperties.getCountryCode());
    request.setCodeLength((long) smsProperties.getCodeLength());
    request.setValidTime(smsProperties.getValidTime());
    request.setInterval(smsProperties.getInterval());
    request.setCodeType((long) smsProperties.getCodeType());
    request.setDuplicatePolicy((long) smsProperties.getDuplicatePolicy());
    request.setReturnVerifyCode(false);

    try {
      SendSmsVerifyCodeResponse response = client().getAcsResponse(request);
      validateResponse(response);
    } catch (ClientException e) {
      throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
          "短信验证码发送失败", e);
    }
  }

  @Override
  public boolean supports(String provider) {
    return "aliyun".equalsIgnoreCase(provider)
        || "aliyun-dysms".equalsIgnoreCase(provider)
        || "aliyun-pnvs".equalsIgnoreCase(provider);
  }

  private void validateResponse(SendSmsVerifyCodeResponse response) {
    String responseCode = response.getCode() == null ? "UNKNOWN" : response.getCode();
    if (!SUCCESS_CODE.equals(responseCode)) {
      String requestId = response.getModel() == null
          ? "unknown"
          : response.getModel().getRequestId();
      log.warn(
          "Aliyun PNVS SMS rejected: code={}, requestId={}, message={}",
          responseCode,
          requestId,
          response.getMessage());
      throw new BusinessException(
          ErrorCode.AI_SERVICE_ERROR,
          "短信验证码发送失败（阿里云错误码：" + responseCode + "）");
    }
  }

  private String templateParameters(String code) {
    try {
      long minutes = Math.max(1, smsProperties.getValidTime() / 60);
      return objectMapper.writeValueAsString(Map.of("code", code, "min", minutes));
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "短信模板参数生成失败", e);
    }
  }

  private IAcsClient client() {
    DefaultProfile profile = DefaultProfile.getProfile(
        smsProperties.getRegionId(),
        smsProperties.getAccessKeyId(),
        smsProperties.getAccessKeySecret());
    return new DefaultAcsClient(profile);
  }

  private void ensureConfigured() {
    if (isBlank(smsProperties.getAccessKeyId())
        || isBlank(smsProperties.getAccessKeySecret())
        || isBlank(smsProperties.getSignName())
        || isBlank(smsProperties.getTemplateCode())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "短信服务配置不完整");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
