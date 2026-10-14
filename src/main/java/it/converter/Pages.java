package it.converter;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class Pages {
  @GetMapping({"/", "/index.html"})
  public String home() {
    return "redirect:/archivio";
  }

  @GetMapping("/archivio")
  public String archive() {
    return "forward:/archive.html";
  }

  @GetMapping("/converti")
  public String convert() {
    return "forward:/convert.html";
  }

  @GetMapping("/unisci")
  public String merge() {
    return "forward:/merge.html";
  }

  @GetMapping("/accesso")
  public String login() {
    return "forward:/login.html";
  }
}
