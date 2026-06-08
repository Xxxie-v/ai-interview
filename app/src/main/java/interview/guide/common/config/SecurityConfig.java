package interview.guide.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.JwtAuthenticationFilter;
import interview.guide.modules.auth.security.AuthPrincipalFactory;
import interview.guide.modules.auth.repository.UserRepository;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final ObjectMapper objectMapper;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
            .requestMatchers(
                "/api/auth/login",
                "/api/auth/register",
                "/api/auth/register/phone",
                "/api/auth/sms/send",
                "/api/auth/sms/login",
                "/api/auth/oauth2/*/authorize",
                "/api/auth/oauth2/*/callback",
                "/api/auth/refresh",
                "/api/resumes/health",
                "/actuator/health",
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/ws/**").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/hr/**").hasRole("ADMIN")
            .requestMatchers("/api/**").authenticated()
            .anyRequest().permitAll())
        .exceptionHandling(exception -> exception
            .authenticationEntryPoint((request, response, authException) ->
                writeError(response, ErrorCode.UNAUTHORIZED, "请先登录"))
            .accessDeniedHandler((request, response, accessDeniedException) ->
                writeError(response, ErrorCode.FORBIDDEN, "无权访问")))
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public UserDetailsService userDetailsService(
      UserRepository userRepository,
      AuthPrincipalFactory principalFactory) {
    return username -> userRepository.findByUsernameIgnoreCase(username)
        .map(principalFactory::fromUser)
        .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
  }

  private void writeError(
      jakarta.servlet.http.HttpServletResponse response,
      ErrorCode errorCode,
      String message) throws IOException {
    response.setStatus(200);
    response.setCharacterEncoding("UTF-8");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), Result.error(errorCode, message));
  }
}
