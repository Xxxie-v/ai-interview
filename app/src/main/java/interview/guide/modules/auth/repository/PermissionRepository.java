package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.PermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<PermissionEntity, String> {
}
