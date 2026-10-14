package it.converter;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class DatabaseMigrationTest {
  @TempDir Path directory;

  DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("test.db"));
  }

  Flyway flyway(String... locations) {
    return Flyway.configure()
        .dataSource(dataSource())
        .locations(locations)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .cleanDisabled(true)
        .validateMigrationNaming(true)
        .load();
  }

  JdbcTemplate legacy() {
    var ds = dataSource();
    new ResourceDatabasePopulator(new ClassPathResource("legacy-schema.sql")).execute(ds);
    var db = new JdbcTemplate(ds);
    db.update("INSERT INTO users VALUES ('old-user', 'existing', 'stored-password-hash')");
    db.update("INSERT INTO tokens VALUES ('old-token', 'local:old-user', 9999999999)");
    db.update(
        "INSERT INTO conversions VALUES ('old-file', 'local:old-user', 'report.docx', 'DOCX', 123,"
            + " 456, '2026-10-14T10:00:00Z')");
    return db;
  }

  @Test
  void freshDatabaseAndRepeatedStartup() {
    var migration = flyway("classpath:db/migration");
    assertEquals(2, migration.migrate().migrationsExecuted);
    var db = new JdbcTemplate(dataSource());
    assertEquals(
        3,
        db.queryForObject(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN"
                + " ('users','tokens','conversions')",
            Integer.class));
    assertEquals("2", migration.info().current().getVersion().toString());
    assertEquals(0, migration.migrate().migrationsExecuted);
    assertEquals(2, db.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
  }

  @Test
  void adoptsLegacyDatabaseWithoutChangingData() {
    var db = legacy();
    var users = db.queryForList("SELECT * FROM users");
    var tokens = db.queryForList("SELECT * FROM tokens");
    var conversions = db.queryForList("SELECT * FROM conversions");
    var migration = flyway("classpath:db/migration");
    assertEquals(2, migration.migrate().migrationsExecuted);
    assertEquals(users, db.queryForList("SELECT * FROM users"));
    assertEquals(tokens, db.queryForList("SELECT * FROM tokens"));
    assertEquals(
        conversions,
        db.queryForList("SELECT id,owner,name,format,size,pdf_size,created FROM conversions"));
    assertEquals(
        "0",
        db.queryForObject(
            "SELECT version FROM flyway_schema_history WHERE type='BASELINE'", String.class));
    assertEquals("2", migration.info().current().getVersion().toString());
    assertEquals(0, migration.migrate().migrationsExecuted);
  }

  @Test
  void futureMigrationPreservesDataAndRunsOnlyOnce() throws Exception {
    var db = legacy();
    flyway("classpath:db/migration").migrate();
    Path next = directory.resolve("migrations");
    Files.createDirectory(next);
    Files.writeString(
        next.resolve("V3__add_label.sql"),
        "ALTER TABLE conversions ADD COLUMN label TEXT NOT NULL DEFAULT '';\n"
            + "UPDATE conversions SET label = 'imported';");
    var migration = flyway("classpath:db/migration", "filesystem:" + next);
    assertEquals(1, migration.migrate().migrationsExecuted);
    assertEquals("3", migration.info().current().getVersion().toString());
    assertEquals(
        "imported",
        db.queryForObject("SELECT label FROM conversions WHERE id='old-file'", String.class));
    assertEquals(
        "report.docx",
        db.queryForObject("SELECT name FROM conversions WHERE id='old-file'", String.class));
    assertEquals(
        "stored-password-hash", db.queryForObject("SELECT password FROM users", String.class));
    db.update("UPDATE conversions SET label='edited'");
    assertEquals(0, migration.migrate().migrationsExecuted);
    assertEquals("edited", db.queryForObject("SELECT label FROM conversions", String.class));
    Files.writeString(
        next.resolve("V3__add_label.sql"),
        "ALTER TABLE conversions ADD COLUMN another_label TEXT;");
    assertThrows(FlywayException.class, migration::migrate);
  }

  @Test
  void failingMigrationRollsBackAndDoesNotAdvanceVersion() throws Exception {
    var db = legacy();
    flyway("classpath:db/migration").migrate();
    Path next = directory.resolve("migrations");
    Files.createDirectory(next);
    Files.writeString(
        next.resolve("V3__broken_upgrade.sql"),
        "ALTER TABLE users ADD COLUMN test_column TEXT;\n"
            + "DELETE FROM users;\n"
            + "INSERT INTO missing_table VALUES (1);");
    var migration = flyway("classpath:db/migration", "filesystem:" + next);
    assertThrows(FlywayException.class, migration::migrate);
    assertEquals("2", migration.info().current().getVersion().toString());
    assertEquals(1, db.queryForObject("SELECT count(*) FROM users", Integer.class));
    assertFalse(
        db.queryForList("PRAGMA table_info(users)").stream()
            .anyMatch(c -> "test_column".equals(c.get("name"))));
  }
}
