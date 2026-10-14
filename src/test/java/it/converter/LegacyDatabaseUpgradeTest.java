package it.converter;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LegacyDatabaseUpgradeTest {
  static final Path DATA;
  static final byte[] ORIGINAL = "previously stored original document".getBytes();
  static final byte[] PDF = "%PDF-1.4\npreviously stored converted document".getBytes();
  static final String KEY = Base64.getEncoder().encodeToString(new byte[48]);

  static {
    try {
      DATA = Files.createTempDirectory("converter-legacy-upgrade-");
      var ds = new DriverManagerDataSource("jdbc:sqlite:" + DATA.resolve("converter.db"));
      new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(ds);
      var db = new JdbcTemplate(ds);
      db.update(
          "INSERT INTO users VALUES (?,?,?)",
          "legacy-user",
          "existing-user",
          new BCryptPasswordEncoder().encode("existing-password-long"));
      db.update("INSERT INTO tokens VALUES ('existing-token','local:legacy-user',9999999999)");
      db.update(
          "INSERT INTO conversions VALUES (?,?,?,?,?,?,?)",
          "legacy-conversion",
          "local:legacy-user",
          "existing.docx",
          "DOCX",
          ORIGINAL.length,
          PDF.length,
          "2026-10-14T10:00:00Z");
      Path files = DATA.resolve("files/legacy-conversion");
      Files.createDirectories(files);
      Files.write(files.resolve("source.docx"), ORIGINAL);
      Files.write(files.resolve("source.pdf"), PDF);
      Files.writeString(DATA.resolve("jwt.key"), KEY);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("app.data-dir", () -> DATA.toString());
    // An existing instance must start without requiring new bootstrap credentials.
    r.add("app.local-password", () -> "");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;

  @Test
  void existingInstanceStillLogsInAndDownloadsHistoryAfterAutomaticMigration() throws Exception {
    String body =
        mvc.perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        "{\"username\":\"existing-user\",\"password\":\"existing-password-long\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = json.readTree(body).get("token").asText();
    mvc.perform(get("/api/conversions").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("existing.docx"));
    assertArrayEquals(
        ORIGINAL,
        mvc.perform(
                get("/api/conversions/legacy-conversion/original")
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
    assertArrayEquals(
        PDF,
        mvc.perform(
                get("/api/conversions/legacy-conversion/pdf")
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
    assertEquals(KEY, Files.readString(DATA.resolve("jwt.key")));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM users", Integer.class));
    assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM tokens WHERE id='existing-token'", Integer.class));
    assertEquals(
        3,
        db.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success=1", Integer.class));
  }

  @AfterAll
  static void cleanup() throws Exception {
    ConversionService.deleteTree(DATA);
  }
}
