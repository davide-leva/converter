package it.converter;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AcceptanceTest {
  static final Path DATA;

  static {
    try {
      DATA = Files.createTempDirectory("converter-test-");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("app.data-dir", () -> DATA.toString());
    r.add("app.local-password", () -> "test-password-long");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  String login() throws Exception {
    return json.readTree(
            mvc.perform(
                    post("/api/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"test-password-long\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("token")
        .asText();
  }

  @Test
  void acceptanceAndIsolation() throws Exception {
    String token = login();
    Path fixtures = Path.of("target/fixtures");
    Files.createDirectories(fixtures);
    List<String> ids = new ArrayList<>();
    for (String ext : List.of("docx", "pptx")) {
      byte[] original;
      try (var out = new ByteArrayOutputStream()) {
        if (ext.equals("docx")) {
          try (var doc = new XWPFDocument()) {
            doc.createParagraph()
                .createRun()
                .setText("Documento di accettazione Forma — conversione PDF");
            doc.write(out);
          }
        } else {
          try (var ppt = new XMLSlideShow()) {
            var box = ppt.createSlide().createTextBox();
            box.setAnchor(new java.awt.geom.Rectangle2D.Double(50, 50, 600, 100));
            box.setText("Presentazione di accettazione Forma");
            ppt.write(out);
          }
        }
        original = out.toByteArray();
      }
      Files.write(fixtures.resolve("sample." + ext), original);
      String body =
          mvc.perform(
                  multipart("/api/conversions")
                      .file(
                          new MockMultipartFile(
                              "file", "sample." + ext, "application/octet-stream", original))
                      .header("Authorization", "Bearer " + token)
                      .with(csrf()))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String id = json.readTree(body).get("id").asText();
      ids.add(id);
      assertArrayEquals(
          original,
          mvc.perform(
                  get("/api/conversions/" + id + "/original")
                      .header("Authorization", "Bearer " + token))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsByteArray());
      assertTrue(
          new String(
                  mvc.perform(
                          get("/api/conversions/" + id + "/pdf")
                              .header("Authorization", "Bearer " + token))
                      .andExpect(status().isOk())
                      .andReturn()
                      .getResponse()
                      .getContentAsByteArray(),
                  java.nio.charset.StandardCharsets.ISO_8859_1)
              .startsWith("%PDF-"));
    }
    mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/conversions").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    token = login();
    mvc.perform(get("/api/conversions").header("Authorization", "Bearer " + token))
        .andExpect(jsonPath("$.length()").value(2));
    for (String id : ids)
      for (String type : List.of("original", "pdf"))
        mvc.perform(
                get("/api/conversions/" + id + "/" + type)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"second\",\"password\":\"another-long-password\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"second\",\"password\":\"another-long-password\"}"))
        .andExpect(status().isConflict());
    String second =
        json.readTree(
                mvc.perform(
                        post("/api/auth/login")
                            .with(csrf())
                            .contentType("application/json")
                            .content(
                                "{\"username\":\"second\",\"password\":\"another-long-password\"}"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("token")
            .asText();
    mvc.perform(get("/api/conversions").header("Authorization", "Bearer " + second))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(
            get("/api/conversions/" + ids.get(0) + "/pdf")
                .header("Authorization", "Bearer " + second))
        .andExpect(status().isNotFound());
    mvc.perform(
            multipart("/api/conversions")
                .file(
                    new MockMultipartFile(
                        "file", "bad.docx", "application/octet-stream", "invalid".getBytes()))
                .header("Authorization", "Bearer " + token)
                .with(csrf()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            multipart("/api/conversions")
                .file(new MockMultipartFile("file", "bad.txt", "text/plain", "invalid".getBytes()))
                .header("Authorization", "Bearer " + token)
                .with(csrf()))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @AfterAll
  static void cleanup() throws IOException {
    ConversionService.deleteTree(DATA);
  }
}
