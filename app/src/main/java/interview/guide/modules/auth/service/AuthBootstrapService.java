package interview.guide.modules.auth.service;

import interview.guide.common.config.AdminUserProperties;
import interview.guide.modules.auth.model.PermissionEntity;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.PermissionRepository;
import interview.guide.modules.auth.repository.RoleRepository;
import interview.guide.modules.auth.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AuthBootstrapService {

  public static final String ROLE_ADMIN = "ADMIN";
  public static final String ROLE_INTERVIEWEE = "INTERVIEWEE";
  public static final String ROLE_TEST_INTERVIEWEE = "TEST_INTERVIEWEE";
  private static final String LEGACY_ROLE_HR = "HR";
  private static final String LEGACY_ROLE_CANDIDATE = "CANDIDATE";

  private final PermissionRepository permissionRepository;
  private final RoleRepository roleRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AdminUserProperties adminUserProperties;
  private final EntityManager entityManager;
  private final TransactionTemplate transactionTemplate;

  @PostConstruct
  public void seedRbac() {
    transactionTemplate.executeWithoutResult(status -> seedRbacInTransaction());
  }

  @Transactional
  public void seedRbacInTransaction() {
    PermissionEntity appAccess = savePermission("APP_ACCESS", "访问应用");
    PermissionEntity adminManage = savePermission("ADMIN_MANAGE", "系统管理");
    PermissionEntity hrView = savePermission("HR_INTERVIEW_VIEW", "查看面试结果");

    RoleEntity interviewee = roleRepository.findById(ROLE_INTERVIEWEE)
        .orElse(RoleEntity.builder().code(ROLE_INTERVIEWEE).name("面试者").build());
    interviewee.setPermissions(new LinkedHashSet<>(List.of(appAccess)));
    roleRepository.save(interviewee);

    RoleEntity testInterviewee = roleRepository.findById(ROLE_TEST_INTERVIEWEE)
        .orElse(RoleEntity.builder()
            .code(ROLE_TEST_INTERVIEWEE)
            .name("测试候选人")
            .build());
    testInterviewee.setPermissions(new LinkedHashSet<>(List.of(appAccess)));
    roleRepository.save(testInterviewee);

    RoleEntity admin = roleRepository.findById(ROLE_ADMIN)
        .orElse(RoleEntity.builder().code(ROLE_ADMIN).name("管理员").build());
    admin.setPermissions(new LinkedHashSet<>(List.of(appAccess, adminManage, hrView)));
    roleRepository.save(admin);

    migrateExistingUsers(admin, interviewee);
    seedDefaultAdmin(admin);
  }

  private PermissionEntity savePermission(String code, String name) {
    PermissionEntity permission = permissionRepository.findById(code)
        .orElse(PermissionEntity.builder().code(code).build());
    permission.setName(name);
    return permissionRepository.save(permission);
  }

  private void seedDefaultAdmin(RoleEntity adminRole) {
    String username = adminUserProperties.getUsername();
    if (username == null || username.isBlank()
        || userRepository.existsByUsernameIgnoreCase(username.trim())) {
      return;
    }
    userRepository.save(UserEntity.builder()
        .username(username.trim())
        .passwordHash(passwordEncoder.encode(adminUserProperties.getPassword()))
        .enabled(true)
        .status(UserStatus.ACTIVE)
        .roles(new HashSet<>(List.of(adminRole)))
        .build());
  }

  private void migrateExistingUsers(RoleEntity adminRole, RoleEntity intervieweeRole) {
    copyLegacyRole(LEGACY_ROLE_HR, adminRole.getCode());
    copyLegacyRole(LEGACY_ROLE_CANDIDATE, intervieweeRole.getCode());
    copyLegacyRole("USER", intervieweeRole.getCode());
    insertIntervieweeRoleForUsersWithoutRoles(intervieweeRole.getCode());
    removeLegacyRoles();
  }

  private void copyLegacyRole(String legacyRole, String targetRole) {
    entityManager.createNativeQuery("""
        insert into auth_user_role (user_id, role_code)
        select user_id, :roleCode
        from auth_user_role
        where role_code = :legacyRole
        on conflict do nothing
        """)
        .setParameter("roleCode", targetRole)
        .setParameter("legacyRole", legacyRole)
        .executeUpdate();
  }

  private void insertIntervieweeRoleForUsersWithoutRoles(String roleCode) {
    entityManager.createNativeQuery("""
        insert into auth_user_role (user_id, role_code)
        select id, :roleCode
        from auth_user user_table
        where not exists (
          select 1
          from auth_user_role user_role
          where user_role.user_id = user_table.id
        )
        on conflict do nothing
        """)
        .setParameter("roleCode", roleCode)
        .executeUpdate();
  }

  private void removeLegacyRoles() {
    entityManager.createNativeQuery("""
        delete from auth_user_role
        where role_code in ('USER', 'CANDIDATE', 'HR')
        """)
        .executeUpdate();
  }
}
