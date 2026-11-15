package com.chaosblade.transfer.web;

import static com.chaosblade.transfer.domain.Model.*;

import com.chaosblade.transfer.matching.*;
import com.chaosblade.transfer.planning.PlanningService;
import com.chaosblade.transfer.profile.*;
import com.chaosblade.transfer.store.SnapshotStore;
import com.chaosblade.transfer.template.TemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/defect-transfer")
public class TransferController {
  private final TemplateService templates;
  private final ProfileService profiles;
  private final MatchingService matching;
  private final PlanningService planning;
  private final RuleRegistry registry;
  private final SnapshotStore store;
  private final SourceHintService sources;

  public TransferController(
      TemplateService templates,
      ProfileService profiles,
      MatchingService matching,
      PlanningService planning,
      RuleRegistry registry,
      SnapshotStore store,
      SourceHintService sources) {
    this.templates = templates;
    this.profiles = profiles;
    this.matching = matching;
    this.planning = planning;
    this.registry = registry;
    this.store = store;
    this.sources = sources;
  }

  public record ImportRequest(@NotBlank @Size(max = 2000000) String markdown) {}

  private Map<String, Object> ok(Object data) {
    return Map.of("success", true, "data", data);
  }

  @GetMapping("/health")
  public Object health() {
    return ok(
        Map.of(
            "status", "UP", "stage", "STATIC_MATCH_AND_ABSTRACT_PLAN", "executionEnabled", false));
  }

  @PostMapping("/templates/import")
  public Object importTemplates(@Valid @RequestBody ImportRequest request) {
    return ok(templates.importMarkdown(request.markdown()));
  }

  @GetMapping("/templates")
  public Object templates() {
    return ok(store.list("templates"));
  }

  @GetMapping("/templates/{id}/versions/{version}")
  public Object template(@PathVariable String id, @PathVariable int version) {
    return ok(templates.get(id, version));
  }

  @GetMapping("/operational-specs")
  public Object specs() {
    return ok(registry.all());
  }

  @PostMapping("/profiles")
  public Object profile(@Valid @RequestBody ProfileInput request) {
    return ok(profiles.create(request));
  }

  @PostMapping("/profiles/source-hints")
  public Object sources(@Valid @RequestBody SourceHintService.Request request) {
    return ok(sources.extract(request));
  }

  @GetMapping("/profiles/{id}")
  public Object profile(@PathVariable String id) {
    return ok(profiles.get(id));
  }

  @PostMapping("/matches")
  public Object match(@Valid @RequestBody MatchRequest request) {
    return ok(matching.match(request));
  }

  @GetMapping("/matches/{id}")
  public Object match(@PathVariable String id) {
    return ok(matching.get(id));
  }

  @PostMapping("/plans")
  public Object plan(@Valid @RequestBody PlanRequest request) {
    return ok(planning.plan(request));
  }

  @GetMapping("/plans/{id}")
  public Object plan(@PathVariable String id) {
    return ok(planning.get(id));
  }
}
