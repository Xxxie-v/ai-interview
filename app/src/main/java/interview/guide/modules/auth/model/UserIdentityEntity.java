package interview.guide.modules.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "auth_user_identity",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_auth_identity_type_identifier",
        columnNames = {"identity_type", "identifier"}),
    indexes = {
        @Index(name = "idx_auth_identity_user", columnList = "user_id"),
        @Index(name = "idx_auth_identity_union", columnList = "identity_type,union_id")
    })
public class UserIdentityEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private UserEntity user;

  @Enumerated(EnumType.STRING)
  @Column(name = "identity_type", nullable = false, length = 16)
  private IdentityType identityType;

  @Column(nullable = false, length = 128)
  private String identifier;

  @Column(name = "union_id", length = 128)
  private String unionId;

  @Column(name = "access_token_encrypted", length = 2048)
  private String accessTokenEncrypted;

  @Column(name = "token_expire_at")
  private LocalDateTime tokenExpireAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void prePersist() {
    createdAt = LocalDateTime.now();
  }
}
