package interview.guide.modules.auth.permission;

import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service("resumePermission")
@RequiredArgsConstructor
public class ResumePermissionService {

  private final ResumeRepository resumeRepository;

  public boolean isOwner(Long resumeId, Authentication authentication) {
    Long userId = PermissionSupport.userId(authentication);
    return userId != null && resumeRepository.findByIdAndOwnerUserId(resumeId, userId).isPresent();
  }
}
