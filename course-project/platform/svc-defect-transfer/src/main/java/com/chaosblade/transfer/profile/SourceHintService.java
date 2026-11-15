package com.chaosblade.transfer.profile;

import static com.chaosblade.transfer.domain.Model.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Limited snippet adapters, not a control-flow or effective-configuration analyser. */
@Service
public class SourceHintService {
  private final ProfileService profiles;
  private final ObjectMapper mapper;

  public SourceHintService(ProfileService profiles, ObjectMapper mapper) {
    this.profiles = profiles;
    this.mapper = mapper;
  }

  public record Snippet(
      @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_.:-]{1,128}") String entityId,
      @NotBlank String path,
      @NotBlank @jakarta.validation.constraints.Pattern(regexp = "go|dotnet") String adapter,
      @NotBlank @Size(max = 500000) String content) {}

  public record Request(
      @NotBlank String systemId,
      @NotBlank String revision,
      @NotEmpty @Size(max = 16) List<@NotNull @Valid Snippet> snippets) {}

  public record Result(Profile profile, List<String> limitations) {}

  public Result extract(Request request) {
    Map<String, Map<String, Fact>> entities = new LinkedHashMap<>();
    List<Evidence> evidence = new ArrayList<>();
    List<String> limitations = new ArrayList<>();
    for (int n = 0; n < request.snippets().size(); n++) {
      Snippet snippet = request.snippets().get(n);
      Map<String, Fact> facts =
          entities.computeIfAbsent(snippet.entityId(), k -> new LinkedHashMap<>());
      String[] lines = snippet.content().split("\\R");
      for (int line = 0; line < lines.length; line++) {
        Map<String, Object> found = new LinkedHashMap<>();
        String text = lines[line].strip();
        if (text.startsWith("//") || text.startsWith("/*") || text.startsWith("*")) continue;
        if (snippet.adapter().equals("go")
            && Pattern.compile("\\.RequiredAcks\\s*=\\s*sarama\\.NoResponse\\b")
                .matcher(text)
                .find()) {
          found.put("publish.acks", 0);
          found.put("capability.publish", true);
        }
        if (snippet.adapter().equals("dotnet")) {
          if (Pattern.compile("\\bEnableAutoCommit\\s*=\\s*true\\b").matcher(text).find())
            found.put("commitMode", "periodic");
          if (Pattern.compile("\\.SaveChanges(?:Async)?\\s*\\(").matcher(text).find())
            found.put("capability.side_effect_write", true);
          if (text.contains("PostgresErrorCodes.UniqueViolation")) found.put("dedup.present", true);
        }
        if (found.isEmpty()) continue;
        String id = "source-" + n + "-" + (line + 1);
        evidence.add(
            new Evidence(
                id,
                EvidenceKind.SOURCE,
                snippet.path() + ":" + (line + 1),
                "Provided snippet only; revision=" + request.revision(),
                text,
                null));
        found.forEach(
            (key, value) -> {
              Fact previous = facts.get(key);
              List<String> refs = new ArrayList<>();
              if (previous != null) refs.addAll(previous.evidenceIds());
              refs.add(id);
              facts.put(
                  key, new Fact(mapper.valueToTree(value), Certainty.INFERRED, List.copyOf(refs)));
            });
      }
    }
    limitations.add(
        "SOURCE_HINTS_ONLY: lexical matches require human review and effective runtime"
            + " configuration");
    limitations.add(
        "No inferred topology, no negative facts from absence, no proof of atomic deduplication or"
            + " full control flow");
    limitations.add(
        "Snippet content and revision are caller supplied; this service does not read host files or"
            + " verify a Git checkout");
    List<Entity> items =
        entities.entrySet().stream()
            .map(e -> new Entity(e.getKey(), "service", e.getValue()))
            .toList();
    Profile p =
        profiles.create(
            new ProfileInput(
                request.systemId(),
                request.revision(),
                "SOURCE_SNAPSHOT",
                items,
                List.of(),
                Map.of(),
                evidence));
    return new Result(p, List.copyOf(limitations));
  }
}
