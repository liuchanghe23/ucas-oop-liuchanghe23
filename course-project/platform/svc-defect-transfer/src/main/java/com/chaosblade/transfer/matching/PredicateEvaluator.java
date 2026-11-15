package com.chaosblade.transfer.matching;

import static com.chaosblade.transfer.domain.Model.*;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Whitelisted structured predicates only. Natural-language template text is never executed. */
@Component
public class PredicateEvaluator {
  private final ObjectMapper mapper;

  public PredicateEvaluator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Assessment evaluate(JsonNode rule, Map<String, String> roles, ProfileInput p) {
    return switch (rule.path("op").asText()) {
      case "EQ" ->
          equalsFact(
              p,
              roles.get(rule.path("subject").asText()),
              rule.path("key").asText(),
              rule.get("value"));
      case "RELATION" ->
          relation(
              p,
              roles.get(rule.path("source").asText()),
              roles.get(rule.path("target").asText()),
              rule.path("type").asText());
      case "ALL", "ANY" -> {
        List<Assessment> children = new ArrayList<>();
        rule.path("operands").forEach(n -> children.add(evaluate(n, roles, p)));
        if (children.isEmpty()) throw new IllegalStateException("Empty operational predicate");
        yield Assessment.combine(rule.path("op").asText().equals("ALL"), children);
      }
      case "NOT" -> evaluate(rule.path("operand"), roles, p).not();
      default -> throw new IllegalStateException("Unregistered operational predicate");
    };
  }

  public Fact fact(ProfileInput p, String subject, String key) {
    if (subject == null) return null;
    if (subject.equals("$system")) return p.facts().get(key);
    return p.entities().stream()
        .filter(e -> e.id().equals(subject))
        .findFirst()
        .map(e -> e.facts().get(key))
        .orElse(null);
  }

  public Assessment equalsFact(ProfileInput p, String subject, String key, Object expected) {
    JsonNode desired = expected instanceof JsonNode j ? j : mapper.valueToTree(expected);
    Fact fact = fact(p, subject, key);
    if (fact == null) {
      // Absence of a capability is negative only with an explicit, evidenced coverage declaration.
      if (key.startsWith("capability.")
          && confirmedBoolean(p.facts().get("capabilities.complete"), true)) {
        Fact coverage = p.facts().get("capabilities.complete");
        return new Assessment(
            desired.isBoolean() && !desired.booleanValue() ? Truth.TRUE : Truth.FALSE,
            coverage.evidenceIds(),
            List.of(),
            "Capability inventory explicitly complete; capability absent: " + subject + "." + key);
      }
      return Assessment.unknown(subject + "." + key);
    }
    if (desired.isBoolean() != fact.value().isBoolean()
        || desired.isTextual() != fact.value().isTextual()) {
      return new Assessment(
          Truth.UNKNOWN,
          fact.evidenceIds(),
          List.of(subject + "." + key + " has the wrong value type"),
          "Type mismatch is not evidence of a false condition");
    }
    if (fact.certainty() != Certainty.CONFIRMED)
      return new Assessment(
          Truth.UNKNOWN,
          fact.evidenceIds(),
          List.of(subject + "." + key + " needs confirmation"),
          "Source hint is not a confirmed fact");
    return new Assessment(
        fact.value().equals(desired) ? Truth.TRUE : Truth.FALSE,
        fact.evidenceIds(),
        List.of(),
        subject + "." + key + "=" + fact.value());
  }

  public Assessment relation(ProfileInput p, String from, String to, String type) {
    var edges =
        p.edges().stream()
            .filter(e -> e.from().equals(from) && e.to().equals(to) && e.type().equals(type))
            .toList();
    if (!edges.isEmpty()) {
      boolean confirmed = edges.stream().anyMatch(e -> e.certainty() == Certainty.CONFIRMED);
      return new Assessment(
          confirmed ? Truth.TRUE : Truth.UNKNOWN,
          edges.stream().flatMap(e -> e.evidenceIds().stream()).distinct().toList(),
          confirmed ? List.of() : List.of("Confirm edge " + from + " -> " + to + " " + type),
          "Explicit " + type + " edge");
    }
    Fact coverage = p.facts().get("topology.complete");
    if (confirmedBoolean(coverage, true))
      return new Assessment(
          Truth.FALSE,
          coverage.evidenceIds(),
          List.of(),
          "Topology explicitly complete; no such edge");
    return Assessment.unknown("edge: " + from + " -> " + to + " " + type);
  }

  public static boolean confirmedBoolean(Fact fact, boolean value) {
    return fact != null
        && fact.certainty() == Certainty.CONFIRMED
        && fact.value().isBoolean()
        && fact.value().booleanValue() == value;
  }
}
