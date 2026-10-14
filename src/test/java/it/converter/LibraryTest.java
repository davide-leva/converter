package it.converter;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class LibraryTest {
  static final Path DATA;

  static {
    try {
      DATA = Files.createTempDirectory("converter-library-test-");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void settings(DynamicPropertyRegistry r) {
    r.add("app.data-dir", () -> DATA.toString());
    r.add("app.local-password", () -> "library-password-long");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  String token;

  @BeforeEach
  void login() throws Exception {
    token = login("admin", "library-password-long");
  }

  String login(String user, String password) throws Exception {
    return json.readTree(
            mvc.perform(
                    post("/api/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content(
                            json.writeValueAsString(
                                Map.of("username", user, "password", password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("token")
        .asText();
  }

  JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString());
  }

  JsonNode folder(String name, String parent) throws Exception {
    return body(
        mvc.perform(
                post("/api/folders")
                    .header("Authorization", "Bearer " + token)
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(new FolderService.FolderRequest(name, parent))))
            .andExpect(status().isCreated())
            .andReturn());
  }

  static byte[] pdf(String text) throws Exception {
    try (var doc = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16);
        stream.newLineAtOffset(50, 700);
        stream.showText(text);
        stream.endText();
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  JsonNode upload(String label, String folder, byte[] bytes) throws Exception {
    var request =
        multipart("/api/documents")
            .file(new MockMultipartFile("file", label + ".pdf", "application/pdf", bytes))
            .param("displayName", label)
            .header("Authorization", "Bearer " + token)
            .with(csrf());
    if (folder != null) request.param("folderId", folder);
    return body(mvc.perform(request).andExpect(status().isCreated()).andReturn());
  }

  MockHttpServletRequestBuilder merge(
      String name, String folder, List<PdfService.MergeItem> items, byte[]... uploads)
      throws Exception {
    var request =
        multipart("/api/merges")
            .file(
                new MockMultipartFile(
                    "request",
                    "request.json",
                    "application/json",
                    json.writeValueAsBytes(new PdfService.MergeRequest(name, folder, items))));
    int i = 0;
    for (var bytes : uploads)
      request.file(
          new MockMultipartFile("files", "upload-" + i++ + ".pdf", "application/pdf", bytes));
    return request.header("Authorization", "Bearer " + token).with(csrf());
  }

  byte[] download(String id, String type) throws Exception {
    return mvc.perform(
            get("/api/conversions/" + id + "/" + type).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsByteArray();
  }

  void assertPdfOrder(byte[] bytes, String... labels) throws Exception {
    try (var doc = Loader.loadPDF(bytes)) {
      assertEquals(labels.length, doc.getNumberOfPages());
      var stripper = new PDFTextStripper();
      for (int i = 0; i < labels.length; i++) {
        stripper.setStartPage(i + 1);
        stripper.setEndPage(i + 1);
        assertEquals(labels[i], stripper.getText(doc).strip());
      }
    }
  }

  @Test
  void foldersNamesMovesAndCycles() throws Exception {
    String root = folder("Progetti " + UUID.randomUUID(), null).get("id").asText(),
        child = folder("Contratti", root).get("id").asText();
    mvc.perform(
            post("/api/folders")
                .header("Authorization", "Bearer " + token)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(new FolderService.FolderRequest("contratti", root))))
        .andExpect(status().isConflict());
    mvc.perform(
            put("/api/folders/" + root)
                .header("Authorization", "Bearer " + token)
                .with(csrf())
                .contentType("application/json")
                .content(json.writeValueAsString(new FolderService.FolderRequest("Ciclo", child))))
        .andExpect(status().isBadRequest());
    var original = pdf("CONTRATTO");
    var file = upload("Contratto cliente", child, original);
    String id = file.get("id").asText();
    assertEquals(child, file.get("folderId").asText());
    mvc.perform(
            delete("/api/folders/" + child).header("Authorization", "Bearer " + token).with(csrf()))
        .andExpect(status().isConflict());
    mvc.perform(
            patch("/api/conversions/" + id)
                .header("Authorization", "Bearer " + token)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new ConversionService.DocumentUpdate("Accordo 2026", root))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Accordo 2026"))
        .andExpect(jsonPath("$.folderId").value(root));
    assertArrayEquals(original, download(id, "original"));
    assertArrayEquals(original, download(id, "pdf"));
    mvc.perform(
            put("/api/folders/" + child)
                .header("Authorization", "Bearer " + token)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new FolderService.FolderRequest("Documenti firmati", null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parentId").isEmpty());
    mvc.perform(
            delete("/api/folders/" + child).header("Authorization", "Bearer " + token).with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  void mixedMergeRespectsOrderAndPreservesSources() throws Exception {
    byte[] a = pdf("PRIMO"), b = pdf("SECONDO"), c = pdf("TERZO");
    var storedA = upload("Documento A", null, a);
    var storedB = upload("Documento B", null, b);
    String folder = folder("Dossier " + UUID.randomUUID(), null).get("id").asText();
    var result =
        body(
            mvc.perform(
                    merge(
                        "Dossier finale",
                        folder,
                        List.of(
                            new PdfService.MergeItem("stored", storedB.get("id").asText(), null),
                            new PdfService.MergeItem("upload", null, 0),
                            new PdfService.MergeItem("stored", storedA.get("id").asText(), null)),
                        c))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("MERGE"))
                .andExpect(jsonPath("$.pages").value(3))
                .andReturn());
    String id = result.get("id").asText();
    assertEquals(folder, result.get("folderId").asText());
    assertPdfOrder(download(id, "pdf"), "SECONDO", "TERZO", "PRIMO");
    assertArrayEquals(a, download(storedA.get("id").asText(), "pdf"));
    assertArrayEquals(b, download(storedB.get("id").asText(), "pdf"));
    assertArrayEquals(c, download(id, "inputs/1"));
    mvc.perform(
            get("/api/conversions/" + id + "/inputs").header("Authorization", "Bearer " + token))
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[1].sourceId").isEmpty());
    String newToken = login("admin", "library-password-long");
    mvc.perform(
            get("/api/conversions/" + id + "/pdf").header("Authorization", "Bearer " + newToken))
        .andExpect(status().isOk());
    Path fixtures = Path.of("target/fixtures");
    Files.createDirectories(fixtures);
    Files.write(fixtures.resolve("first.pdf"), a);
    Files.write(fixtures.resolve("second.pdf"), b);
    Files.write(fixtures.resolve("third.pdf"), c);
  }

  @Test
  void uploadOnlyAndStoredOnlyMerge() throws Exception {
    byte[] a = pdf("ALFA"), b = pdf("BETA");
    var uploaded =
        body(
            mvc.perform(
                    merge(
                        "Upload uniti",
                        null,
                        List.of(
                            new PdfService.MergeItem("upload", null, 1),
                            new PdfService.MergeItem("upload", null, 0)),
                        a,
                        b))
                .andExpect(status().isCreated())
                .andReturn());
    assertPdfOrder(download(uploaded.get("id").asText(), "pdf"), "BETA", "ALFA");
    var first = upload("Solo archivio A", null, a);
    var second = upload("Solo archivio B", null, b);
    var stored =
        body(
            mvc.perform(
                    merge(
                        "Archivio unito",
                        null,
                        List.of(
                            new PdfService.MergeItem("stored", first.get("id").asText(), null),
                            new PdfService.MergeItem("stored", second.get("id").asText(), null))))
                .andExpect(status().isCreated())
                .andReturn());
    assertPdfOrder(download(stored.get("id").asText(), "pdf"), "ALFA", "BETA");
  }

  @Test
  void isolationAndInvalidInputsLeaveNoFiles() throws Exception {
    var file = upload("File privato", null, pdf("PRIVATO"));
    String id = file.get("id").asText(),
        folder = folder("Privata " + UUID.randomUUID(), null).get("id").asText();
    String name = "other-" + UUID.randomUUID().toString().substring(0, 8);
    mvc.perform(
            post("/api/auth/register")
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("username", name, "password", "other-password-long"))))
        .andExpect(status().isOk());
    token = login(name, "other-password-long");
    mvc.perform(get("/api/folders").header("Authorization", "Bearer " + token))
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/conversions/" + id + "/pdf").header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mvc.perform(
            merge(
                "Non autorizzato",
                null,
                List.of(
                    new PdfService.MergeItem("stored", id, null),
                    new PdfService.MergeItem("upload", null, 0)),
                pdf("UPLOAD")))
        .andExpect(status().isNotFound());
    mvc.perform(
            multipart("/api/documents")
                .file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf("UPLOAD")))
                .param("folderId", folder)
                .header("Authorization", "Bearer " + token)
                .with(csrf()))
        .andExpect(status().isNotFound());
    long before;
    try (var paths = Files.list(DATA.resolve("files"))) {
      before = paths.count();
    }
    mvc.perform(
            merge(
                "Danneggiato",
                null,
                List.of(
                    new PdfService.MergeItem("upload", null, 0),
                    new PdfService.MergeItem("upload", null, 1)),
                pdf("OK"),
                "%PDF-broken".getBytes()))
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(
            merge(
                "Sorgente inesistente",
                null,
                List.of(
                    new PdfService.MergeItem("upload", null, 0),
                    new PdfService.MergeItem("upload", null, 9)),
                pdf("OK")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            merge(
                "Un solo file",
                null,
                List.of(new PdfService.MergeItem("upload", null, 0)),
                pdf("OK")))
        .andExpect(status().isBadRequest());
    try (var paths = Files.list(DATA.resolve("files"))) {
      assertEquals(before, paths.count());
    }
    mvc.perform(get("/api/conversions").header("Authorization", "Bearer " + token))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void folderDepthIsCheckedWhenCreatingAndMovingSubtrees() throws Exception {
    String root = folder("Livelli " + UUID.randomUUID(), null).get("id").asText(), parent = root;
    for (int i = 2; i <= 30; i++) parent = folder("Livello " + i, parent).get("id").asText();
    mvc.perform(
            post("/api/folders")
                .header("Authorization", "Bearer " + token)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new FolderService.FolderRequest("Troppo profonda", parent))))
        .andExpect(status().isBadRequest());
    String other = folder("Nuova radice " + UUID.randomUUID(), null).get("id").asText();
    mvc.perform(
            put("/api/folders/" + root)
                .header("Authorization", "Bearer " + token)
                .with(csrf())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new FolderService.FolderRequest("Sposta albero", other))))
        .andExpect(status().isBadRequest());
  }

  @AfterAll
  static void cleanup() throws Exception {
    ConversionService.deleteTree(DATA);
  }
}
