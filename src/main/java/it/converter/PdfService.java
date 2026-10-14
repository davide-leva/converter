package it.converter;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PdfService {
  final ConversionService files;
  final FolderService folders;
  final JdbcTemplate db;
  final TransactionTemplate tx;

  PdfService(
      ConversionService files,
      FolderService folders,
      JdbcTemplate db,
      PlatformTransactionManager tm) {
    this.files = files;
    this.folders = folders;
    this.db = db;
    this.tx = new TransactionTemplate(tm);
  }

  public record MergeItem(String type, String id, Integer index) {}

  public record MergeRequest(String name, String folderId, List<MergeItem> items) {}

  public record Input(int position, String sourceId, String name) {}

  Path newDirectory() throws IOException {
    Path dir = files.fileRoot().resolve(UUID.randomUUID().toString());
    Files.createDirectory(dir);
    return dir;
  }

  static String uploadName(MultipartFile file) {
    String raw = Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\', '/');
    String name = raw.substring(raw.lastIndexOf('/') + 1);
    if (file.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".pdf"))
      throw ConversionService.bad("Carica un file PDF non vuoto");
    return FolderService.label(name);
  }

  static PDDocument open(Path path) throws IOException {
    try {
      PDDocument doc = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache());
      if (doc.isEncrypted() || doc.getNumberOfPages() < 1) {
        doc.close();
        throw ConversionService.bad(
            "PDF protetto o senza pagine: scegli un documento non protetto");
      }
      return doc;
    } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
      throw ConversionService.bad("I PDF protetti da password non sono supportati");
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "PDF non valido o danneggiato");
    }
  }

  void acquire() {
    files.acquireWork();
  }

  ConversionService.Conversion upload(
      MultipartFile file, String owner, String folderId, String displayName) throws Exception {
    String name = uploadName(file),
        label =
            displayName == null || displayName.isBlank() ? name : FolderService.label(displayName),
        folder = folders.validateParent(folderId, owner);
    acquire();
    Path dir = null;
    boolean saved = false;
    try {
      dir = newDirectory();
      Path pdf = dir.resolve("source.pdf");
      file.transferTo(pdf);
      int pages;
      try (var doc = open(pdf)) {
        pages = doc.getNumberOfPages();
        if (pages > 2500) throw ConversionService.bad("Il PDF supera il limite di 2500 pagine");
      }
      var c =
          new ConversionService.Conversion(
              dir.getFileName().toString(),
              name,
              "PDF",
              Files.size(pdf),
              Files.size(pdf),
              Instant.now().toString(),
              label,
              folder,
              "UPLOAD",
              pages);
      files.insert(c, owner);
      saved = true;
      return c;
    } finally {
      try {
        if (!saved && dir != null) ConversionService.deleteTree(dir);
      } finally {
        files.releaseWork();
      }
    }
  }

  ConversionService.Conversion merge(MergeRequest req, List<MultipartFile> uploads, String owner)
      throws Exception {
    String label = FolderService.label(req.name()),
        folder = folders.validateParent(req.folderId(), owner);
    if (req.items() == null || req.items().size() < 2 || req.items().size() > 50)
      throw ConversionService.bad("Scegli da 2 a 50 PDF da unire");
    List<MultipartFile> uploaded = uploads == null ? List.of() : uploads;
    // Resolve ownership and indices before creating files or parsing PDFs.
    Map<String, ConversionService.Conversion> stored = new HashMap<>();
    Set<Integer> used = new HashSet<>();
    for (var item : req.items()) {
      if (item == null) throw ConversionService.bad("Sorgente PDF mancante");
      if ("stored".equals(item.type())) stored.put(item.id(), files.find(item.id(), owner));
      else if ("upload".equals(item.type())
          && item.index() != null
          && item.index() >= 0
          && item.index() < uploaded.size()) {
        uploadName(uploaded.get(item.index()));
        used.add(item.index());
      } else throw ConversionService.bad("Sorgente PDF non valida");
    }
    if (used.size() != uploaded.size())
      throw ConversionService.bad("Ogni file caricato deve essere incluso nell'unione");
    acquire();
    Path dir = null;
    boolean saved = false;
    try {
      dir = newDirectory();
      Files.createDirectory(dir.resolve("inputs"));
      List<Input> inputs = new ArrayList<>();
      int pages = 0;
      long total = 0;
      try (var output = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
        var merger = new PDFMergerUtility();
        int position = 0;
        for (var item : req.items()) {
          Path source;
          String name, sourceId = null;
          if ("stored".equals(item.type())) {
            var c = stored.get(item.id());
            source = files.path(c, true);
            name = c.displayName();
            sourceId = c.id();
          } else {
            var file = uploaded.get(item.index());
            name = uploadName(file);
            source = dir.resolve("inputs").resolve(position + ".pdf");
            file.transferTo(source);
          }
          total += Files.size(source);
          if (total > 100L * 1024 * 1024)
            throw ConversionService.bad("L'unione supera il limite totale di 100 MB");
          try (var doc = open(source)) {
            pages += doc.getNumberOfPages();
            if (pages > 2500)
              throw ConversionService.bad("L'unione supera il limite di 2500 pagine");
            merger.appendDocument(output, doc);
          }
          inputs.add(new Input(position++, sourceId, name));
        }
        output.getDocumentInformation().setTitle(label);
        output.save(dir.resolve("source.pdf").toFile());
      }
      String name = label.toLowerCase(Locale.ROOT).endsWith(".pdf") ? label : label + ".pdf";
      long size = Files.size(dir.resolve("source.pdf"));
      var c =
          new ConversionService.Conversion(
              dir.getFileName().toString(),
              name,
              "PDF",
              size,
              size,
              Instant.now().toString(),
              label,
              folder,
              "MERGE",
              pages);
      tx.executeWithoutResult(
          status -> {
            files.insert(c, owner);
            for (var input : inputs)
              db.update(
                  "INSERT INTO merge_inputs(merge_id,position,source_id,name) VALUES (?,?,?,?)",
                  c.id(),
                  input.position(),
                  input.sourceId(),
                  input.name());
          });
      saved = true;
      return c;
    } finally {
      try {
        if (!saved && dir != null) ConversionService.deleteTree(dir);
      } finally {
        files.releaseWork();
      }
    }
  }

  List<Input> inputs(String id, String owner) {
    files.find(id, owner);
    return db.query(
        "SELECT * FROM merge_inputs WHERE merge_id=? ORDER BY position",
        (r, i) -> new Input(r.getInt("position"), r.getString("source_id"), r.getString("name")),
        id);
  }

  Path inputPath(String id, int position, String owner) {
    var input =
        inputs(id, owner).stream()
            .filter(i -> i.position() == position)
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sorgente non trovata"));
    return input.sourceId() == null
        ? files.fileRoot().resolve(id).resolve("inputs").resolve(position + ".pdf")
        : files.path(files.find(input.sourceId(), owner), true);
  }
}
