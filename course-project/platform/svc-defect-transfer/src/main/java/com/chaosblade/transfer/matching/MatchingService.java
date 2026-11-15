package com.chaosblade.transfer.matching;

import static com.chaosblade.transfer.domain.Model.*;

import com.chaosblade.transfer.profile.ProfileService;
import com.chaosblade.transfer.store.SnapshotStore;
import com.chaosblade.transfer.template.TemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MatchingService {
  private final TemplateService templates;
  private final ProfileService profiles;
  private final PredicateEvaluator evaluator;
  private final RuleRegistry registry;
  private final SnapshotStore store;

  public MatchingService(
      TemplateService templates,
      ProfileService profiles,
      PredicateEvaluator evaluator,
      RuleRegistry registry,
      SnapshotStore store) {
    this.templates = templates;
    this.profiles = profiles;
    this.evaluator = evaluator;
    this.registry = registry;
    this.store = store;
  }

  public Match match(MatchRequest request) {
    Template template = templates.get(request.templateId(), request.templateVersion());
    Profile profile = profiles.get(request.profileId());
    JsonNode rule = registry.find(template);
    List<String> limitations = new ArrayList<>();
    if (template.status().equals("draft"))
      limitations.add("TEMPLATE_DRAFT: incident provenance is not verified");
    List<Binding> bindings = new ArrayList<>();
    boolean complete = false;
    String suitability = "UNKNOWN";
    if (rule == null)
      limitations.add(
          "RULESET_NOT_IMPLEMENTED: no rules for this exact template version and source hash");
    else {
      limitations.add("RULESET_REVIEW_REQUIRED: curated checks; no runtime conclusion");
      List<String> roleNames = new ArrayList<>();
      template.document().path("roles").fieldNames().forEachRemaining(roleNames::add);
      enumerate(
          0,
          roleNames,
          new LinkedHashMap<>(),
          template,
          rule,
          profile.snapshot(),
          request.maxBindings(),
          bindings);
      complete = bindings.size() <= request.maxBindings();
      if (!complete) {
        bindings.remove(bindings.size() - 1);
        limitations.add(
            "ENUMERATION_TRUNCATED: raise maxBindings or narrow the profile; no global negative"
                + " conclusion");
      }
      if (bindings.isEmpty())
        limitations.add(
            "NO_COMPLETE_ROLE_BINDING: provide the required components and capabilities; absence of"
                + " a binding alone proves no negative");
      boolean covered =
          List.of("inventory.complete", "capabilities.complete", "topology.complete").stream()
              .allMatch(
                  k ->
                      PredicateEvaluator.confirmedBoolean(profile.snapshot().facts().get(k), true));
      if (!covered)
        limitations.add("PROFILE_COVERAGE_INCOMPLETE: cannot exclude unobserved roles or edges");
      if (bindings.stream().anyMatch(b -> b.applicability() == Truth.TRUE))
        suitability = "APPLICABLE";
      else if (complete
          && covered
          && bindings.stream().allMatch(b -> b.applicability() == Truth.FALSE))
        suitability = "NOT_APPLICABLE";
    }
    Match result =
        new Match(
            UUID.randomUUID().toString(),
            Instant.now().toString(),
            template.templateId(),
            template.version(),
            template.sourceHash(),
            rule == null ? null : rule.path("version").asText(),
            profile.id(),
            profile.snapshot().environment(),
            suitability,
            complete,
            List.copyOf(limitations),
            List.copyOf(bindings));
    store.put("matches", result.id(), result);
    return result;
  }

  private void enumerate(
      int index,
      List<String> names,
      LinkedHashMap<String, String> bound,
      Template template,
      JsonNode rule,
      ProfileInput profile,
      int limit,
      List<Binding> out) {
    if (out.size() > limit) return;
    if (index == names.size()) {
      out.add(assess(bound, template, rule, profile));
      return;
    }
    String role = names.get(index);
    JsonNode spec = template.document().path("roles").path(role);
    boolean optional = spec.path("optional").asBoolean(false);
    List<Entity> candidates = new ArrayList<>(profile.entities());
    candidates.removeIf(
        e ->
            (!optional && bound.containsValue(e.id()))
                || capabilities(e.id(), spec, profile).truth() == Truth.FALSE);
    // Prefer evidenced capabilities and already-known edges. No service names appear in the rules.
    candidates.sort(
        Comparator.<Entity>comparingInt(e -> score(e.id(), role, spec, bound, profile))
            .reversed()
            .thenComparing(Entity::id));
    for (Entity e : candidates) {
      bound.put(role, e.id());
      enumerate(index + 1, names, bound, template, rule, profile, limit, out);
      bound.remove(role);
      if (out.size() > limit) return;
    }
    if (optional) enumerate(index + 1, names, bound, template, rule, profile, limit, out);
  }

  private int score(
      String id, String role, JsonNode spec, Map<String, String> bound, ProfileInput p) {
    int score = capabilities(id, spec, p).truth() == Truth.TRUE ? 10 : 0;
    for (var edge : p.edges())
      if (edge.certainty() == Certainty.CONFIRMED) {
        if ((edge.from().equals(id) && bound.containsValue(edge.to()))
            || (edge.to().equals(id) && bound.containsValue(edge.from()))) score++;
      }
    return score;
  }

  private Assessment capabilities(String id, JsonNode spec, ProfileInput p) {
    List<Assessment> checks = new ArrayList<>();
    spec.path("needs")
        .forEach(n -> checks.add(evaluator.equalsFact(p, id, "capability." + n.asText(), true)));
    return Assessment.combine(true, checks);
  }

  private Binding assess(
      Map<String, String> bound, Template template, JsonNode rule, ProfileInput profile) {
    Map<String, String> roles = Collections.unmodifiableMap(new LinkedHashMap<>(bound));
    List<Condition> conditions = new ArrayList<>();
    roles.forEach(
        (role, id) -> {
          conditions.add(
              new Condition(
                  "R." + role,
                  "Role capabilities: "
                      + template.document().path("roles").path(role).path("needs"),
                  capabilities(id, template.document().path("roles").path(role), profile)));
          if (role.equals("guard"))
            conditions.add(
                new Condition(
                    "R.guard.scope",
                    "guard protects this consumer",
                    evaluator.relation(profile, id, roles.get("consumer"), "PROTECTS")));
        });
    rule.path("applicability").forEach(c -> conditions.add(condition(c, roles, profile, template)));
    Truth applicable = Truth.all(conditions.stream().map(c -> c.result().truth()).toList());
    List<Condition> defects = new ArrayList<>();
    rule.path("defects").forEach(c -> defects.add(condition(c, roles, profile, template)));
    conditions.addAll(defects);
    List<Truth> values = defects.stream().map(c -> c.result().truth()).toList();
    Truth defect =
        rule.path("defectOperator").asText().equals("ALL") ? Truth.all(values) : Truth.any(values);
    return new Binding(roles, List.copyOf(conditions), applicable, defect);
  }

  private Condition condition(
      JsonNode rule, Map<String, String> roles, ProfileInput p, Template t) {
    String id = rule.path("id").asText();
    Assessment result = evaluator.evaluate(rule.path("predicate"), roles, p);
    if (t.templateId().equals("DT-MQ-REDELIVER-001") && id.equals("D1")) {
      // A visible guard remains visible even when this optional role was omitted from a binding.
      List<Assessment> guards = new ArrayList<>();
      for (Entity entity : p.entities()) {
        Assessment relation = evaluator.relation(p, entity.id(), roles.get("consumer"), "PROTECTS");
        Assessment capability = evaluator.equalsFact(p, entity.id(), "capability.dedup", true);
        if (relation.truth() == Truth.TRUE && capability.truth() == Truth.TRUE)
          guards.add(Assessment.combine(true, List.of(relation, capability)));
      }
      if (!guards.isEmpty()) {
        List<String> refs = new ArrayList<>(result.evidenceIds());
        guards.forEach(g -> refs.addAll(g.evidenceIds()));
        if (result.truth() == Truth.TRUE)
          result =
              new Assessment(
                  Truth.UNKNOWN,
                  refs.stream().distinct().toList(),
                  List.of(
                      "INCONSISTENT_GUARD_EVIDENCE: guard protects consumer but"
                          + " dedup.present=false"),
                  "Resolve contradictory evidence");
        else
          result =
              new Assessment(
                  Truth.FALSE,
                  refs.stream().distinct().toList(),
                  List.of(),
                  "An evidenced dedup guard protects this consumer");
      }
    }
    return new Condition(id, findExpression(t.document(), id), result);
  }

  private String findExpression(JsonNode node, String id) {
    if (node.isObject() && node.path("id").asText().equals(id)) return node.path("expr").asText();
    for (JsonNode child : node) {
      String found = findExpression(child, id);
      if (found != null) return found;
    }
    return null;
  }

  public Match get(String id) {
    return store.get("matches", id, Match.class);
  }
}
