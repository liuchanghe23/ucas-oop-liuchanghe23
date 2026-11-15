package com.chaosblade.transfer.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

public final class Model {
  private Model() {}

  public enum Truth {
    TRUE,
    FALSE,
    UNKNOWN;

    public Truth not() {
      return this == TRUE ? FALSE : this == FALSE ? TRUE : UNKNOWN;
    }

    public static Truth all(Collection<Truth> values) {
      if (values.contains(FALSE)) return FALSE;
      return values.contains(UNKNOWN) ? UNKNOWN : TRUE;
    }

    public static Truth any(Collection<Truth> values) {
      if (values.contains(TRUE)) return TRUE;
      return values.contains(UNKNOWN) ? UNKNOWN : FALSE;
    }
  }

  public enum Certainty {
    CONFIRMED,
    INFERRED,
    UNKNOWN
  }

  public enum EvidenceKind {
    SOURCE,
    CONFIG,
    RUNTIME,
    MANUAL,
    SYNTHETIC
  }

  public record Evidence(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,128}") String id,
      @NotNull EvidenceKind kind,
      @NotBlank String locator,
      @NotBlank String scope,
      @NotBlank String detail,
      String reviewer) {}

  public record Fact(
      @NotNull JsonNode value,
      @NotNull Certainty certainty,
      @NotEmpty List<@NotBlank String> evidenceIds) {}

  public record Entity(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,128}") String id,
      @NotBlank String kind,
      @NotNull Map<@NotBlank String, @NotNull @Valid Fact> facts) {}

  public record Edge(
      @NotBlank String from,
      @NotBlank String to,
      @NotBlank @Pattern(regexp = "PUBLISHES|DELIVERS|PROTECTS") String type,
      @NotNull Certainty certainty,
      @NotEmpty List<@NotBlank String> evidenceIds) {}

  public record ProfileInput(
      @NotBlank String systemId,
      @NotBlank String revision,
      @NotBlank @Pattern(regexp = "SYNTHETIC|SOURCE_SNAPSHOT|DEPLOYMENT") String environment,
      @NotNull @Size(max = 64) List<@NotNull @Valid Entity> entities,
      @NotNull @Size(max = 1024) List<@NotNull @Valid Edge> edges,
      @NotNull Map<@NotBlank String, @NotNull @Valid Fact> facts,
      @NotNull @Size(max = 2048) List<@NotNull @Valid Evidence> evidence) {}

  public record Profile(String id, String createdAt, ProfileInput snapshot) {}

  public record Template(
      String templateId,
      int version,
      String status,
      String sourceHash,
      String importedAt,
      String yaml,
      JsonNode document) {}

  public record Assessment(
      Truth truth, List<String> evidenceIds, List<String> missing, String reason) {
    public static Assessment unknown(String missing) {
      return new Assessment(
          Truth.UNKNOWN, List.of(), List.of(missing), "Missing confirmed evidence for this scope");
    }

    public Assessment not() {
      return new Assessment(truth.not(), evidenceIds, missing, "NOT: " + reason);
    }

    public static Assessment combine(boolean all, List<Assessment> items) {
      return new Assessment(
          all
              ? Truth.all(items.stream().map(Assessment::truth).toList())
              : Truth.any(items.stream().map(Assessment::truth).toList()),
          items.stream().flatMap(x -> x.evidenceIds.stream()).distinct().toList(),
          items.stream().flatMap(x -> x.missing.stream()).distinct().toList(),
          (all ? "ALL" : "ANY") + items.stream().map(x -> x.truth.name()).toList());
    }
  }

  public record Condition(String id, String expression, Assessment result) {}

  public record Binding(
      Map<String, String> roles,
      List<Condition> conditions,
      Truth applicability,
      Truth defectConditions) {}

  public record Match(
      String id,
      String createdAt,
      String templateId,
      int templateVersion,
      String templateHash,
      String rulesetVersion,
      String profileId,
      String environment,
      String suitability,
      boolean enumerationComplete,
      List<String> limitations,
      List<Binding> bindings) {}

  public record MatchRequest(
      @NotBlank String templateId,
      @Min(1) int templateVersion,
      @NotBlank String profileId,
      @Min(1) @Max(512) int maxBindings) {}

  public record Catalog(
      @NotBlank String id,
      @NotBlank String version,
      @NotNull Set<@NotBlank String> executorCapabilities,
      @NotNull Set<@NotBlank String> observerCapabilities,
      @NotEmpty List<@NotBlank String> evidenceIds) {}

  public record Workload(
      @NotBlank String description,
      @NotBlank String businessKey,
      @Positive double ratePerSecond,
      @Positive int requests) {}

  public record Limits(
      @Positive long maxFaultDurationMs,
      @Positive double maxRatePerSecond,
      @Positive int maxRequests) {}

  public record PlanRequest(
      @NotBlank String matchId,
      @Min(0) int bindingIndex,
      @NotBlank @Pattern(regexp = "PRECISE|PROBABILISTIC") String strategy,
      @NotNull @Valid Catalog catalog,
      @NotNull @Valid Workload workload,
      @NotNull @Valid Limits limits) {}

  public record Blocker(String code, String detail) {}

  public record Parameter(long value, String unit, List<String> evidenceIds, String derivation) {}

  public record Observation(String dimension, String rule, List<String> capabilities) {}

  public record Plan(
      String id,
      String createdAt,
      String matchId,
      int bindingIndex,
      String templateHash,
      String rulesetVersion,
      String profileId,
      String environment,
      String readiness,
      boolean reviewInputsComplete,
      boolean executionEnabled,
      List<Blocker> blockers,
      String faultType,
      Map<String, String> targets,
      String injectPoint,
      String strategy,
      Map<String, Parameter> parameters,
      Workload workload,
      Limits limits,
      Catalog declaredCatalog,
      List<Observation> observations,
      List<String> steps,
      String conclusion) {}
}
