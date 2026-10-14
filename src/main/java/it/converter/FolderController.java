package it.converter;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/folders")
public class FolderController {
  final FolderService folders;

  FolderController(FolderService folders) {
    this.folders = folders;
  }

  @GetMapping
  List<FolderService.Folder> list(Authentication a) {
    return folders.list(AuthController.owner(a));
  }

  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  FolderService.Folder create(@RequestBody FolderService.FolderRequest req, Authentication a) {
    return folders.create(req, AuthController.owner(a));
  }

  @PutMapping("/{id}")
  FolderService.Folder update(
      @PathVariable String id, @RequestBody FolderService.FolderRequest req, Authentication a) {
    return folders.update(id, req, AuthController.owner(a));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id, Authentication a) {
    folders.delete(id, AuthController.owner(a));
  }
}
