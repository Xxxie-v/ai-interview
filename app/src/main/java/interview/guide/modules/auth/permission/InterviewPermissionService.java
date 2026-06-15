package interview.guide.modules.auth.permission;

import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service("interviewPermission")
@RequiredArgsConstructor
public class InterviewPermissionService {

  private final InterviewSessionRepository interviewSessionRepository;
  private final VoiceInterviewSessionRepository voiceSessionRepository;

  public boolean isOwner(String sessionId, Authentication authentication) {
    Long userId = PermissionSupport.userId(authentication);
    return userId != null
        && interviewSessionRepository.findBySessionIdAndOwnerUserId(sessionId, userId).isPresent();
  }

  public boolean isVoiceOwner(Long sessionId, Authentication authentication) {
    Long userId = PermissionSupport.userId(authentication);
    return userId != null && voiceSessionRepository
        .findByIdAndUserId(sessionId, String.valueOf(userId)).isPresent();
  }

  public boolean canViewVoice(Long sessionId, Authentication authentication) {
    return PermissionSupport.isAdmin(authentication) || isVoiceOwner(sessionId, authentication);
  }
}
