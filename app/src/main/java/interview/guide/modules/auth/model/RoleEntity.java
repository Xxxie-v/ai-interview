package interview.guide.modules.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "auth_role")
public class RoleEntity {

  @Id
  @Column(length = 32)
  private String code;

  @Column(nullable = false, length = 64)
  private String name;

  @Builder.Default
  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "auth_role_permission",
      joinColumns = @JoinColumn(name = "role_code"),
      inverseJoinColumns = @JoinColumn(name = "permission_code"))
  private Set<PermissionEntity> permissions = new HashSet<>();
}
