package it.converter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class Security {
  static String token(HttpServletRequest req) {
    String h = req.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer ")) return h.substring(7);
    if (req.getCookies() != null)
      for (var c : req.getCookies()) if (c.getName().equals("CONVERTER_TOKEN")) return c.getValue();
    return null;
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http, Settings settings, AuthService auth)
      throws Exception {
    var cookieRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
    cookieRepo.setCookieCustomizer(c -> c.secure(settings.secure).sameSite("Lax").path("/"));
    http.csrf(
            c ->
                c.csrfTokenRepository(cookieRepo)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/index.html",
                        "/archivio",
                        "/converti",
                        "/unisci",
                        "/accesso",
                        "/archive.html",
                        "/convert.html",
                        "/merge.html",
                        "/login.html",
                        "/assets/**",
                        "/app.js",
                        "/style.css",
                        "/api/auth/config",
                        "/api/auth/csrf",
                        "/api/auth/login",
                        "/api/auth/register",
                        "/health",
                        "/oauth2/**",
                        "/login/**",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) -> {
                      res.setStatus(401);
                      res.setContentType("application/json");
                      res.getWriter().write("{\"message\":\"Accesso richiesto\"}");
                    }))
        .logout(
            l ->
                l.logoutUrl("/api/auth/logout")
                    .addLogoutHandler(
                        (req, res, a) -> {
                          String t = token(req);
                          if (t != null) auth.revoke(t);
                        })
                    .deleteCookies("CONVERTER_TOKEN", "JSESSIONID")
                    .logoutSuccessHandler((req, res, a) -> res.setStatus(204)))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'; img-src"
                                + " 'self' data:; object-src 'none'; base-uri 'none';"
                                + " frame-ancestors 'none'")))
        .addFilterBefore(
            new OncePerRequestFilter() {
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                String t = token(req);
                if (!settings.oidc() && t != null) {
                  try {
                    var jwt = auth.verify(t);
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(jwt, null, List.of()));
                  } catch (org.springframework.security.oauth2.jwt.JwtException ex) {
                    SecurityContextHolder.clearContext();
                  }
                }
                chain.doFilter(req, res);
              }
            },
            UsernamePasswordAuthenticationFilter.class);
    if (settings.oidc()) {
      var registration =
          ClientRegistrations.fromIssuerLocation(settings.issuer)
              .registrationId("authentik")
              .clientId(settings.clientId)
              .clientSecret(settings.clientSecret)
              .clientAuthenticationMethod(new ClientAuthenticationMethod(settings.authMethod))
              .scope(settings.scopes.split(","))
              .redirectUri(settings.url + "/login/oauth2/code/authentik")
              .clientName("Authentik")
              .build();
      http.oauth2Login(
          o ->
              o.clientRegistrationRepository(new InMemoryClientRegistrationRepository(registration))
                  .defaultSuccessUrl(settings.url + "/", true)
                  .failureUrl(settings.url + "/accesso?auth_error"));
    } else
      http.securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()));
    return http.build();
  }
}
