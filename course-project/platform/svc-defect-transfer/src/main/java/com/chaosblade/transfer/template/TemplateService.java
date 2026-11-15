package com.chaosblade.transfer.template;

import static com.chaosblade.transfer.domain.Model.*;

import com.chaosblade.transfer.store.SnapshotStore;
import com.chaosblade.transfer.web.Problem;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;

@Service
public class TemplateService {
  private final SnapshotStore store;
  private final ObjectMapper yaml =
      new ObjectMapper(
          YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
  private static final Pattern FENCE = Pattern.compile("(?ms)^```ya?ml[ \t]*\n(.*?)^```[ \t]*$");

  public TemplateService(SnapshotStore store) {
    this.store = store;
  }

  public record Imported(
      String templateId, int version, boolean created, String status, String sourceHash) {}

  public synchronized List<Imported> importMarkdown(String markdown) {
    if (markdown == null || markdown.length() > 2_000_000)
      throw Problem.bad("INVALID_TEMPLATE", "Expected Markdown up to 2 MB");
    var matcher = FENCE.matcher(markdown.replace("\r\n", "\n"));
    List<Template> parsed = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    while (matcher.find()) {
      String raw = matcher.group(1).strip();
      JsonNode doc;
      try (var parser = yaml.createParser(raw)) {
        doc = yaml.readTree(parser);
        if (parser.nextToken() != null)
          throw Problem.bad("INVALID_TEMPLATE", "One YAML document per fence is required");
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw Problem.bad("INVALID_YAML", "Invalid YAML: " + e.getOriginalMessage());
      } catch (java.io.IOException e) {
        throw Problem.bad("INVALID_YAML", "Cannot read YAML");
      }
      validate(doc);
      String id = doc.path("template_id").asText();
      int version = doc.path("version").asInt();
      String key = id + "-v" + version;
      if (!keys.add(key))
        throw Problem.bad("DUPLICATE_TEMPLATE", "Repeated template version in import: " + key);
      String hash = SnapshotStore.hash(raw);
      Template item =
          new Template(
              id, version, doc.path("status").asText(), hash, Instant.now().toString(), raw, doc);
      // Validate the entire batch before writing any record.
      if (store.exists("templates", key)) {
        Template existing = get(id, version);
        if (!existing.sourceHash().equals(hash))
          throw new Problem(
              409, "VERSION_CONFLICT", "Template version already has different content: " + key);
        item = existing;
      }
      parsed.add(item);
    }
    if (parsed.isEmpty()) throw Problem.bad("INVALID_TEMPLATE", "No YAML template fences found");
    List<Imported> result = new ArrayList<>();
    for (Template item : parsed) {
      boolean created = store.put("templates", key(item.templateId(), item.version()), item);
      result.add(
          new Imported(
              item.templateId(), item.version(), created, item.status(), item.sourceHash()));
    }
    return result;
  }

  private void validate(JsonNode doc) {
    if (doc == null
        || !doc.isObject()
        || !doc.path("template_id").asText().matches("[A-Z0-9-]{1,128}")
        || !doc.path("version").canConvertToInt()
        || !doc.path("version").isIntegralNumber()
        || doc.path("version").asInt() < 1
        || !doc.path("status").isTextual()
        || doc.path("status").asText().isBlank())
      throw Problem.bad(
          "INVALID_TEMPLATE", "template_id, positive integer version and status are required");
    for (String field : List.of("name", "mechanism", "summary"))
      if (!doc.path(field).isTextual() || doc.path(field).asText().isBlank())
        throw Problem.bad("INVALID_TEMPLATE", "Missing text: " + field);
    for (String field :
        List.of("roles", "defect_conditions", "activation", "oracles", "provenance"))
      if (!doc.path(field).isObject())
        throw Problem.bad("INVALID_TEMPLATE", "Missing object: " + field);
    if (doc.path("roles").isEmpty()
        || !doc.path("applicability").isArray()
        || doc.path("applicability").isEmpty())
      throw Problem.bad("INVALID_TEMPLATE", "roles and applicability cannot be empty");
    doc.path("roles")
        .properties()
        .forEach(
            e -> {
              if (!e.getKey().matches("[a-z_]+")
                  || !e.getValue().path("needs").isArray()
                  || e.getValue().path("needs").isEmpty())
                throw Problem.bad("INVALID_TEMPLATE", "Every role requires a needs array");
              e.getValue()
                  .path("needs")
                  .forEach(
                      n -> {
                        if (!n.isTextual() || n.asText().isBlank())
                          throw Problem.bad("INVALID_TEMPLATE", "Invalid role capability");
                      });
            });
    Set<String> ids = new HashSet<>();
    doc.path("applicability").forEach(c -> validateCondition(c, ids));
    validateDefects(doc.path("defect_conditions"), ids);
    for (String field : List.of("fault_type", "target", "inject_point", "workload"))
      if (!doc.path("activation").path(field).isTextual())
        throw Problem.bad("INVALID_TEMPLATE", "Missing activation." + field);
    for (String field : List.of("activation", "consequence", "invariant"))
      if (!doc.path("oracles").path(field).isTextual())
        throw Problem.bad("INVALID_TEMPLATE", "Missing oracle: " + field);
  }

  private void validateDefects(JsonNode node, Set<String> ids) {
    if (node.has("id")) {
      validateCondition(node, ids);
      return;
    }
    boolean all = node.has("all_of"), any = node.has("any_of");
    if (all == any)
      throw Problem.bad("INVALID_TEMPLATE", "Defects need exactly one all_of/any_of group");
    JsonNode children = node.path(all ? "all_of" : "any_of");
    if (!children.isArray() || children.isEmpty())
      throw Problem.bad("INVALID_TEMPLATE", "Defect group cannot be empty");
    children.forEach(c -> validateDefects(c, ids));
  }

  private void validateCondition(JsonNode node, Set<String> ids) {
    if (!node.path("id").asText().matches("[AD][1-9][0-9]*")
        || !ids.add(node.path("id").asText())
        || !node.path("expr").isTextual())
      throw Problem.bad("INVALID_TEMPLATE", "Invalid or repeated condition ID/expr");
  }

  private String key(String id, int version) {
    if (id == null || !id.matches("[A-Z0-9-]{1,128}") || version < 1)
      throw Problem.bad("INVALID_TEMPLATE_ID", "Invalid template ID/version");
    return id + "-v" + version;
  }

  public Template get(String id, int version) {
    return store.get("templates", key(id, version), Template.class);
  }
}
