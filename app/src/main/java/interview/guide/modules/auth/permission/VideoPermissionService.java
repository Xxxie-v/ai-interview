package interview.guide.modules.auth.permission;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service("videoPermission")
@RequiredArgsConstructor
public class VideoPermissionService {

  private final InterviewPermissionService interviewPermissionService;

  public boolean canAccessVoiceSession(Long sessionId, Authentication authentication) {
    return interviewPermissionService.canViewVoice(sessionId, authentication);
  }
}
