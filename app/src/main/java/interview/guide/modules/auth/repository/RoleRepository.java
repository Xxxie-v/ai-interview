package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<RoleEntity, String> {
}
