package it.converter;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FolderService {
  final JdbcTemplate db;

  FolderService(JdbcTemplate db) {
    this.db = db;
  }

  public record Folder(String id, String name, String parentId, String created) {}

  public record FolderRequest(String name, String parentId) {}

  List<Folder> list(String owner) {
    return db.query(
        "SELECT * FROM folders WHERE owner=? ORDER BY name COLLATE NOCASE",
        (r, i) ->
            new Folder(
                r.getString("id"),
                r.getString("name"),
                r.getString("parent_id"),
                r.getString("created")),
        owner);
  }

  Folder find(String id, String owner) {
    return db
        .query(
            "SELECT * FROM folders WHERE id=? AND owner=?",
            (r, i) ->
                new Folder(
                    r.getString("id"),
                    r.getString("name"),
                    r.getString("parent_id"),
                    r.getString("created")),
            id,
            owner)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cartella non trovata"));
  }

  String validateParent(String id, String owner) {
    if (id == null || id.isBlank()) return null;
    find(id, owner);
    return id;
  }

  static String label(String value) {
    if (value == null
        || value.isBlank()
        || value.strip().length() > 180
        || value.matches("(?s).*[\\p{Cntrl}/\\\\].*"))
      throw ConversionService.bad(
          "Il nome deve contenere da 1 a 180 caratteri, senza slash o caratteri di controllo");
    return value.strip();
  }

  void hierarchy(String parent, String id, String owner) {
    Set<String> seen = new HashSet<>();
    int depth = 0;
    while (parent != null) {
      if (parent.equals(id) || !seen.add(parent))
        throw ConversionService.bad(
            "Una cartella non può contenere sé stessa o una sua cartella superiore");
      if (++depth > 30) throw ConversionService.bad("Limite di 30 livelli di cartelle raggiunto");
      parent = find(parent, owner).parentId();
    }
    int height =
        db.queryForObject(
            "WITH RECURSIVE subtree(id,depth) AS (SELECT id,1 FROM folders WHERE id=? AND owner=?"
                + " UNION ALL SELECT f.id,s.depth+1 FROM folders f JOIN subtree s ON"
                + " f.parent_id=s.id WHERE f.owner=?) SELECT COALESCE(MAX(depth),1) FROM subtree",
            Integer.class,
            id,
            owner,
            owner);
    if (depth + height > 30)
      throw ConversionService.bad("Limite di 30 livelli di cartelle raggiunto");
  }

  @Transactional
  public Folder create(FolderRequest req, String owner) {
    String name = label(req.name()),
        parent = validateParent(req.parentId(), owner),
        id = UUID.randomUUID().toString();
    hierarchy(parent, id, owner);
    try {
      db.update(
          "INSERT INTO folders(id,owner,parent_id,name,created) VALUES (?,?,?,?,?)",
          id,
          owner,
          parent,
          name,
          Instant.now().toString());
    } catch (org.springframework.dao.DataAccessException e) {
      conflict(e);
    }
    return find(id, owner);
  }

  @Transactional
  public Folder update(String id, FolderRequest req, String owner) {
    find(id, owner);
    String name = label(req.name()), parent = validateParent(req.parentId(), owner);
    hierarchy(parent, id, owner);
    try {
      db.update(
          "UPDATE folders SET name=?,parent_id=? WHERE id=? AND owner=?", name, parent, id, owner);
    } catch (org.springframework.dao.DataAccessException e) {
      conflict(e);
    }
    return find(id, owner);
  }

  @Transactional
  public void delete(String id, String owner) {
    find(id, owner);
    if (db.queryForObject(
            "SELECT (SELECT count(*) FROM folders WHERE parent_id=?) + (SELECT count(*) FROM"
                + " conversions WHERE folder_id=?)",
            Integer.class,
            id,
            id)
        > 0)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "La cartella contiene file o sottocartelle. Spostali prima di eliminarla");
    db.update("DELETE FROM folders WHERE id=? AND owner=?", id, owner);
  }

  static void conflict(org.springframework.dao.DataAccessException e) {
    if (e.getMostSpecificCause() instanceof org.sqlite.SQLiteException sql
        && sql.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Esiste già una cartella con questo nome nella stessa posizione");
    throw e;
  }
}
