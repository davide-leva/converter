package it.converter;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversionService {
  final Settings settings;
  final JdbcTemplate db;
  final FolderService folders;
  final Semaphore capacity = new Semaphore(2);

  ConversionService(Settings settings, JdbcTemplate db, FolderService folders) throws IOException {
    this.settings = settings;
    this.db = db;
    this.folders = folders;
    Files.createDirectories(settings.data.resolve("files"));
  }

  public Path fileRoot() {
    return settings.data.resolve("files");
  }

  public void acquireWork() {
    if (!capacity.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Elaborazioni occupate: riprova tra poco");
  }

  public void releaseWork() {
    capacity.release();
  }

  public record Conversion(
      String id,
      String name,
      String format,
      long size,
      long pdfSize,
      String created,
      String displayName,
      String folderId,
      String kind,
      Integer pages) {}

  org.springframework.jdbc.core.RowMapper<Conversion> mapper() {
    return (r, i) ->
        new Conversion(
            r.getString("id"),
            r.getString("name"),
            r.getString("format"),
            r.getLong("size"),
            r.getLong("pdf_size"),
            r.getString("created"),
            r.getString("display_name"),
            r.getString("folder_id"),
            r.getString("kind"),
            r.getObject("pages") == null ? null : r.getInt("pages"));
  }

  List<Conversion> history(String owner) {
    return db.query(
        "SELECT * FROM conversions WHERE owner=? ORDER BY created DESC", mapper(), owner);
  }

  Conversion find(String id, String owner) {
    return db
        .query("SELECT * FROM conversions WHERE id=? AND owner=?", mapper(), id, owner)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File non trovato"));
  }

  void insert(Conversion c, String owner) {
    db.update(
        "INSERT INTO"
            + " conversions(id,owner,name,format,size,pdf_size,created,display_name,folder_id,kind,pages)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        c.id(),
        owner,
        c.name(),
        c.format(),
        c.size(),
        c.pdfSize(),
        c.created(),
        c.displayName(),
        c.folderId(),
        c.kind(),
        c.pages());
  }

  public record DocumentUpdate(String displayName, String folderId) {}

  @org.springframework.transaction.annotation.Transactional
  public Conversion update(String id, DocumentUpdate req, String owner) {
    find(id, owner);
    String name = FolderService.label(req.displayName()),
        folder = folders.validateParent(req.folderId(), owner);
    db.update(
        "UPDATE conversions SET display_name=?,folder_id=? WHERE id=? AND owner=?",
        name,
        folder,
        id,
        owner);
    return find(id, owner);
  }

  Path path(Conversion c, boolean pdf) {
    return settings
        .data
        .resolve("files")
        .resolve(c.id())
        .resolve(pdf ? "source.pdf" : "source." + c.format().toLowerCase(Locale.ROOT));
  }

  Conversion convert(MultipartFile upload, String owner) throws Exception {
    return convert(upload, owner, null, null);
  }

  Conversion convert(MultipartFile upload, String owner, String folderId, String displayName)
      throws Exception {
    String destination = folders.validateParent(folderId, owner);
    String raw = Optional.ofNullable(upload.getOriginalFilename()).orElse("").replace('\\', '/');
    String name = raw.substring(raw.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (name.length() > 180 || name.isBlank() || upload.isEmpty())
      throw bad("File vuoto o nome non valido");
    String label =
        displayName == null || displayName.isBlank() ? name : FolderService.label(displayName);
    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (!Set.of("docx", "pptx").contains(ext)) throw bad("Sono supportati solo DOCX e PPTX");
    if (!capacity.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Conversioni occupate: riprova tra poco");
    Path dir = settings.data.resolve("files").resolve(UUID.randomUUID().toString());
    boolean saved = false;
    Process process = null;
    try {
      Files.createDirectory(dir);
      Path original = dir.resolve("source." + ext);
      upload.transferTo(original);
      validate(original, ext);
      Path profile = dir.resolve("profile");
      process =
          new ProcessBuilder(
                  settings.libreoffice,
                  "-env:UserInstallation=" + profile.toUri(),
                  "--headless",
                  "--nologo",
                  "--nodefault",
                  "--nofirststartwizard",
                  "--convert-to",
                  "pdf",
                  "--outdir",
                  dir.toString(),
                  original.toString())
              .redirectErrorStream(true)
              .redirectOutput(dir.resolve("conversion.log").toFile())
              .start();
      if (!process.waitFor(settings.timeout, TimeUnit.SECONDS)) {
        kill(process);
        throw new ResponseStatusException(
            HttpStatus.GATEWAY_TIMEOUT, "La conversione ha superato il tempo massimo");
      }
      Path pdf = dir.resolve("source.pdf");
      if (process.exitValue() != 0 || !Files.isRegularFile(pdf) || Files.size(pdf) < 5)
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "Impossibile convertire il documento");
      try (var in = Files.newInputStream(pdf)) {
        if (!new String(in.readNBytes(5), java.nio.charset.StandardCharsets.US_ASCII)
            .equals("%PDF-")) throw bad("Output PDF non valido");
      }
      deleteTree(profile);
      Files.deleteIfExists(dir.resolve("conversion.log"));
      var c =
          new Conversion(
              dir.getFileName().toString(),
              name,
              ext.toUpperCase(Locale.ROOT),
              Files.size(original),
              Files.size(pdf),
              Instant.now().toString(),
              label,
              destination,
              "CONVERSION",
              null);
      insert(c, owner);
      saved = true;
      return c;
    } finally {
      if (process != null && process.isAlive()) kill(process);
      try {
        if (!saved) deleteTree(dir);
      } finally {
        capacity.release();
      }
    }
  }

  static void kill(Process p) {
    p.descendants().forEach(ProcessHandle::destroyForcibly);
    p.destroyForcibly();
    try {
      p.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static void validate(Path p, String ext) throws IOException {
    try (ZipFile zip = new ZipFile(p.toFile())) {
      if (zip.getEntry("[Content_Types].xml") == null
          || zip.getEntry(ext.equals("docx") ? "word/document.xml" : "ppt/presentation.xml")
              == null) throw bad("Il contenuto non corrisponde al formato dichiarato");
      long total = 0;
      int entries = 0;
      var e = zip.entries();
      byte[] buffer = new byte[8192];
      while (e.hasMoreElements()) {
        var entry = e.nextElement();
        if (++entries > 10000) throw bad("Documento troppo complesso");
        try (var in = zip.getInputStream(entry)) {
          int n;
          while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > 200L * 1024 * 1024) throw bad("Documento decompresso troppo grande");
          }
        }
      }
    } catch (java.util.zip.ZipException ex) {
      throw bad("Documento Office non valido");
    }
  }

  static void deleteTree(Path p) throws IOException {
    if (Files.exists(p))
      try (var paths = Files.walk(p)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
  }

  static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
