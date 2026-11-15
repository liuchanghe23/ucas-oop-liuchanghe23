package com.chaosblade.transfer.store;

import com.chaosblade.transfer.web.Problem;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Single-instance immutable JSON snapshots. Never replace an existing snapshot. */
@Component
public class SnapshotStore {
  private final Path root;
  private final ObjectMapper mapper;

  public SnapshotStore(@Value("${transfer.store-dir}") String directory, ObjectMapper mapper)
      throws IOException {
    this.root = Path.of(directory).toAbsolutePath().normalize();
    this.mapper = mapper;
    Files.createDirectories(root);
  }

  private Path path(String kind, String id) {
    if (!kind.matches("templates|profiles|matches|plans") || !id.matches("[A-Za-z0-9_.-]{1,180}"))
      throw Problem.bad("INVALID_ID", "Invalid snapshot identifier");
    return root.resolve(kind).resolve(id + ".json");
  }

  public synchronized boolean put(String kind, String id, Object value) {
    Path target = path(kind, id);
    JsonNode node = mapper.valueToTree(value);
    if (Files.exists(target)) {
      if (read(kind, id).equals(node)) return false;
      throw new Problem(409, "VERSION_CONFLICT", "Snapshot already has different content: " + id);
    }
    Path temp = null;
    try {
      Files.createDirectories(target.getParent());
      temp = Files.createTempFile(target.getParent(), "snapshot-", ".tmp");
      mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), node);
      try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target);
      }
      return true;
    } catch (IOException e) {
      throw new IllegalStateException("Snapshot storage error", e);
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
    }
  }

  public synchronized boolean exists(String kind, String id) {
    return Files.exists(path(kind, id));
  }

  public synchronized JsonNode read(String kind, String id) {
    Path target = path(kind, id);
    if (!Files.exists(target))
      throw new Problem(404, "SNAPSHOT_NOT_FOUND", "Snapshot not found: " + id);
    try {
      return mapper.readTree(target.toFile());
    } catch (IOException e) {
      throw new IllegalStateException("Snapshot storage error", e);
    }
  }

  public <T> T get(String kind, String id, Class<T> type) {
    try {
      return mapper.treeToValue(read(kind, id), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Snapshot storage error", e);
    }
  }

  public synchronized List<JsonNode> list(String kind) {
    Path dir = path(kind, "index").getParent();
    if (!Files.exists(dir)) return List.of();
    try (var paths = Files.list(dir)) {
      return paths
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .sorted()
          .map(p -> read(kind, p.getFileName().toString().replaceFirst("\\.json$", "")))
          .toList();
    } catch (IOException e) {
      throw new IllegalStateException("Snapshot storage error", e);
    }
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
