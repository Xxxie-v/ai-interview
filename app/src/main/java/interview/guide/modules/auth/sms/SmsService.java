package interview.guide.modules.auth.sms;

public interface SmsService {

  void sendCode(String mobile, String code);

  boolean supports(String provider);
}
