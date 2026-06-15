package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.IdentityType;
import interview.guide.modules.auth.model.UserIdentityEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, Long> {

  Optional<UserIdentityEntity> findByIdentityTypeAndIdentifier(
      IdentityType identityType, String identifier);

  Optional<UserIdentityEntity> findByIdentityTypeAndUnionId(
      IdentityType identityType, String unionId);

  boolean existsByUserIdAndIdentityType(Long userId, IdentityType identityType);
}
