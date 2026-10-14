package it.converter;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class AuthService {
  final JdbcTemplate db;
  final Settings settings;
  final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  final JwtEncoder encoder;
  final JwtDecoder decoder;

  public AuthService(JdbcTemplate db, Settings settings) throws Exception {
    this.db = db;
    this.settings = settings;
    byte[] key;
    if (!settings.secret.isBlank())
      key = settings.secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    else {
      Path p = settings.data.resolve("jwt.key");
      if (!Files.exists(p)) {
        byte[] b = new byte[48];
        new SecureRandom().nextBytes(b);
        Files.writeString(p, Base64.getEncoder().encodeToString(b), StandardOpenOption.CREATE_NEW);
        try {
          Files.setPosixFilePermissions(
              p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
        }
      }
      key = Base64.getDecoder().decode(Files.readString(p).trim());
    }
    if (key.length < 32) throw new IllegalArgumentException("JWT_SECRET richiede almeno 32 byte");
    encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    decoder =
        NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    if (!settings.oidc() && db.queryForObject("SELECT count(*) FROM users", Integer.class) == 0) {
      if (settings.password.length() < 12)
        throw new IllegalArgumentException(
            "Al primo avvio impostare LOCAL_PASSWORD (almeno 12 caratteri)");
      register(settings.user, settings.password);
    }
    db.update("DELETE FROM tokens WHERE expires < ?", Instant.now().getEpochSecond());
  }

  Map<String, Object> register(String username, String password) {
    if (username == null
        || !username.matches("[A-Za-z0-9_.@-]{3,80}")
        || password == null
        || password.length() < 12
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Username: 3–80 caratteri; password: 12–72 caratteri");
    String id = UUID.randomUUID().toString();
    try {
      db.update("INSERT INTO users VALUES (?,?,?)", id, username, passwords.encode(password));
    } catch (org.springframework.dao.DataAccessException e) {
      if (e.getMostSpecificCause() instanceof org.sqlite.SQLiteException sql
          && sql.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Username già utilizzato");
      throw e;
    }
    return Map.of("id", id, "username", username);
  }

  String login(String username, String password) {
    var users =
        db.queryForList("SELECT * FROM users WHERE username=?", username == null ? "" : username);
    if (users.isEmpty()
        || password == null
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
        || !passwords.matches(password, users.get(0).get("password").toString()))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenziali non valide");
    Instant now = Instant.now(), expires = now.plusSeconds(settings.jwtHours * 3600);
    String jti = UUID.randomUUID().toString(), subject = "local:" + users.get(0).get("id");
    db.update("INSERT INTO tokens VALUES (?,?,?)", jti, subject, expires.getEpochSecond());
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder()
                    .issuer(settings.url)
                    .subject(subject)
                    .id(jti)
                    .issuedAt(now)
                    .expiresAt(expires)
                    .claim("name", username)
                    .build()))
        .getTokenValue();
  }

  Jwt verify(String token) {
    Jwt jwt = decoder.decode(token);
    if (!settings.url.equals(jwt.getIssuer().toString())
        || db.queryForObject(
                "SELECT count(*) FROM tokens WHERE id=? AND user_id=? AND expires>?",
                Integer.class,
                jwt.getId(),
                jwt.getSubject(),
                Instant.now().getEpochSecond())
            != 1) throw new JwtException("Token revocato");
    return jwt;
  }

  void revoke(String token) {
    try {
      db.update("DELETE FROM tokens WHERE id=?", decoder.decode(token).getId());
    } catch (JwtException ignored) {
    }
  }
}
