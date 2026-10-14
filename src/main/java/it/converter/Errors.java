package it.converter;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> response(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(Map.of("message", e.getReason() == null ? "Richiesta non valida" : e.getReason()));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> size() {
    return ResponseEntity.status(413)
        .body(Map.of("message", "Il file supera la dimensione massima consentita"));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class
  })
  ResponseEntity<?> invalid() {
    return ResponseEntity.badRequest()
        .body(Map.of("message", "Richiesta non valida o file mancante"));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> missing() {
    return ResponseEntity.status(404).body(Map.of("message", "Risorsa non trovata"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class).error("Errore richiesta", e);
    return ResponseEntity.internalServerError()
        .body(Map.of("message", "Errore interno durante l'elaborazione"));
  }
}
