package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.RefreshTokenEntity;
import interview.guide.modules.auth.model.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  void deleteByUser(UserEntity user);
}
