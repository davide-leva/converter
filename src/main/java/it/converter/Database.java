package it.converter;

import java.nio.file.Files;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class Database {
  @Bean
  DataSource dataSource(Settings settings) throws Exception {
    Files.createDirectories(settings.data);
    var ds =
        new DriverManagerDataSource(
            "jdbc:sqlite:"
                + settings.data.resolve("converter.db")
                + "?busy_timeout=10000&foreign_keys=on");
    ds.setDriverClassName("org.sqlite.JDBC");
    var db = new JdbcTemplate(ds);
    db.execute("PRAGMA journal_mode=WAL");
    return ds;
  }
}
