package it.converter;

import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class PdfController {
  final PdfService pdfs;

  PdfController(PdfService pdfs) {
    this.pdfs = pdfs;
  }

  @PostMapping("/api/documents")
  @ResponseStatus(HttpStatus.CREATED)
  ConversionService.Conversion upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String folderId,
      @RequestParam(required = false) String displayName,
      Authentication a)
      throws Exception {
    return pdfs.upload(file, AuthController.owner(a), folderId, displayName);
  }

  @PostMapping("/api/merges")
  @ResponseStatus(HttpStatus.CREATED)
  ConversionService.Conversion merge(
      @RequestPart("request") PdfService.MergeRequest req,
      @RequestPart(value = "files", required = false) List<MultipartFile> files,
      Authentication a)
      throws Exception {
    return pdfs.merge(req, files, AuthController.owner(a));
  }

  @GetMapping("/api/conversions/{id}/inputs")
  List<PdfService.Input> inputs(@PathVariable String id, Authentication a) {
    return pdfs.inputs(id, AuthController.owner(a));
  }

  @GetMapping("/api/conversions/{id}/inputs/{position}")
  ResponseEntity<FileSystemResource> input(
      @PathVariable String id, @PathVariable int position, Authentication a) {
    String owner = AuthController.owner(a);
    var source =
        pdfs.inputs(id, owner).stream()
            .filter(i -> i.position() == position)
            .findFirst()
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND));
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(
                    source.name().replaceFirst("(?i)\\.pdf$", "") + ".pdf",
                    java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(new FileSystemResource(pdfs.inputPath(id, position, owner)));
  }
}
