package it.converter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/conversions")
public class ConversionController {
  final ConversionService service;

  ConversionController(ConversionService service) {
    this.service = service;
  }

  @GetMapping
  List<ConversionService.Conversion> history(Authentication a) {
    return service.history(AuthController.owner(a));
  }

  @PostMapping
  ResponseEntity<ConversionService.Conversion> convert(
      @RequestParam("file") MultipartFile file,
      @RequestParam(required = false) String folderId,
      @RequestParam(required = false) String displayName,
      Authentication a)
      throws Exception {
    return ResponseEntity.status(201)
        .body(service.convert(file, AuthController.owner(a), folderId, displayName));
  }

  @PatchMapping("/{id}")
  ConversionService.Conversion update(
      @PathVariable String id,
      @RequestBody ConversionService.DocumentUpdate req,
      Authentication a) {
    return service.update(id, req, AuthController.owner(a));
  }

  @GetMapping("/{id}/{kind:original|pdf}")
  ResponseEntity<FileSystemResource> download(
      @PathVariable String id, @PathVariable String kind, Authentication a) {
    var c = service.find(id, AuthController.owner(a));
    boolean pdf = kind.equals("pdf");
    String title = c.displayName() == null ? c.name() : c.displayName();
    String name = pdf ? title.replaceFirst("(?i)\\.(docx|pptx|pdf)$", "") + ".pdf" : c.name();
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .contentType(
            MediaType.parseMediaType(
                pdf || c.format().equals("PDF")
                    ? "application/pdf"
                    : c.format().equals("DOCX")
                        ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        : "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
        .body(new FileSystemResource(service.path(c, pdf)));
  }
}
