package interview.guide.modules.auth.security;

import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class AuthPrincipalFactory {

  public AuthPrincipal fromUser(UserEntity user) {
    return new AuthPrincipal(
        user.getId(),
        user.getUsername(),
        user.getPasswordHash(),
        user.isLoginAllowed(),
        authorities(user));
  }

  public Set<String> roleCodes(UserEntity user) {
    Set<String> roles = new LinkedHashSet<>();
    for (RoleEntity role : user.getRoles()) {
      roles.add(role.getCode());
    }
    return roles;
  }

  public Set<String> permissionCodes(UserEntity user) {
    Set<String> permissions = new LinkedHashSet<>();
    user.getRoles().forEach(role -> role.getPermissions()
        .forEach(permission -> permissions.add(permission.getCode())));
    return permissions;
  }

  private Collection<? extends GrantedAuthority> authorities(UserEntity user) {
    Set<GrantedAuthority> authorities = new LinkedHashSet<>();
    user.getRoles().forEach(role -> {
      authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getCode()));
      role.getPermissions().forEach(permission ->
          authorities.add(new SimpleGrantedAuthority(permission.getCode())));
    });
    return authorities;
  }
}
