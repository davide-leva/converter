package it.converter;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Settings {
  final Path data;
  final String url,
      user,
      password,
      secret,
      issuer,
      clientId,
      clientSecret,
      scopes,
      authMethod,
      libreoffice;
  final boolean registration, secure;
  final long jwtHours, timeout;

  public Settings(
      @Value("${app.data-dir}") String data,
      @Value("${app.public-url}") String url,
      @Value("${app.local-user}") String user,
      @Value("${app.local-password}") String password,
      @Value("${app.jwt-secret}") String secret,
      @Value("${app.oidc-issuer}") String issuer,
      @Value("${app.oidc-client-id}") String clientId,
      @Value("${app.oidc-client-secret}") String clientSecret,
      @Value("${app.oidc-scopes}") String scopes,
      @Value("${app.oidc-auth-method}") String authMethod,
      @Value("${app.registration}") boolean registration,
      @Value("${app.jwt-hours}") long jwtHours,
      @Value("${app.convert-timeout}") long timeout,
      @Value("${app.libreoffice}") String libreoffice) {
    this.data = Path.of(data).toAbsolutePath().normalize();
    this.url = url.replaceAll("/+$", "");
    URI uri = URI.create(this.url);
    if (uri.getHost() == null
        || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
        || (uri.getPath() != null && !uri.getPath().isEmpty()))
      throw new IllegalArgumentException(
          "PUBLIC_URL deve essere un'origine HTTP/HTTPS senza percorso");
    this.secure = uri.getScheme().equals("https");
    this.user = user;
    this.password = password;
    this.secret = secret;
    this.issuer = issuer;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.scopes = scopes;
    this.authMethod = authMethod;
    this.registration = registration;
    this.jwtHours = jwtHours;
    this.timeout = timeout;
    this.libreoffice = libreoffice;
    if (jwtHours < 1 || timeout < 1) throw new IllegalArgumentException("Durate non valide");
    if ((!issuer.isBlank() || !clientId.isBlank() || !clientSecret.isBlank())
        && (issuer.isBlank() || clientId.isBlank() || clientSecret.isBlank()))
      throw new IllegalArgumentException(
          "Configurare tutti i parametri OIDC: issuer, client ID e secret");
  }

  boolean oidc() {
    return !issuer.isBlank();
  }
}
