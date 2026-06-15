package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

  boolean existsByUsernameIgnoreCase(String username);

  boolean existsByEmailIgnoreCase(String email);

  boolean existsByPhone(String phone);

  Optional<UserEntity> findByUsernameIgnoreCase(String username);

  Optional<UserEntity> findByPhone(String phone);
}
