package it.converter;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthController {
  final Settings settings;
  final AuthService auth;

  AuthController(Settings settings, AuthService auth) {
    this.settings = settings;
    this.auth = auth;
  }

  static String owner(Authentication a) {
    if (a.getPrincipal() instanceof Jwt j) return j.getSubject();
    var u = (OidcUser) a.getPrincipal();
    return "oidc:" + u.getIssuer() + ":" + u.getSubject();
  }

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "UP");
  }

  @GetMapping("/api/auth/config")
  Map<String, Object> config() {
    return Map.of(
        "mode",
        settings.oidc() ? "oidc" : "local",
        "registration",
        !settings.oidc() && settings.registration,
        "loginUrl",
        "/oauth2/authorization/authentik");
  }

  @GetMapping("/api/auth/csrf")
  Map<String, String> csrf(CsrfToken t) {
    return Map.of("token", t.getToken(), "headerName", t.getHeaderName());
  }

  record Credentials(String username, String password) {}

  @PostMapping("/api/auth/login")
  Map<String, Object> login(@RequestBody Credentials c, HttpServletResponse response) {
    local();
    String token = auth.login(c.username(), c.password());
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from("CONVERTER_TOKEN", token)
            .httpOnly(true)
            .secure(settings.secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(settings.jwtHours * 3600)
            .build()
            .toString());
    return Map.of("token", token, "expiresIn", settings.jwtHours * 3600);
  }

  @PostMapping("/api/auth/register")
  Map<String, Object> register(@RequestBody Credentials c) {
    local();
    if (!settings.registration)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Registrazione disabilitata");
    return auth.register(c.username(), c.password());
  }

  @GetMapping("/api/auth/me")
  Map<String, String> me(Authentication a) {
    String name =
        a.getPrincipal() instanceof Jwt j
            ? j.getClaimAsString("name")
            : ((OidcUser) a.getPrincipal()).getFullName();
    return Map.of("id", owner(a), "username", name == null ? a.getName() : name);
  }

  void local() {
    if (settings.oidc())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usare l'accesso OIDC");
  }
}
