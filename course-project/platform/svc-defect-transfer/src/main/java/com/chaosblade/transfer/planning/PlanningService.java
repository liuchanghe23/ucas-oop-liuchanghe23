package com.chaosblade.transfer.planning;

import static com.chaosblade.transfer.domain.Model.*;

import com.chaosblade.transfer.matching.*;
import com.chaosblade.transfer.profile.ProfileService;
import com.chaosblade.transfer.store.SnapshotStore;
import com.chaosblade.transfer.web.Problem;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PlanningService {
  private final MatchingService matches;
  private final ProfileService profiles;
  private final PredicateEvaluator evaluator;
  private final SnapshotStore store;

  public PlanningService(
      MatchingService matches,
      ProfileService profiles,
      PredicateEvaluator evaluator,
      SnapshotStore store) {
    this.matches = matches;
    this.profiles = profiles;
    this.evaluator = evaluator;
    this.store = store;
  }

  public Plan plan(PlanRequest request) {
    Match match = matches.get(request.matchId());
    if (request.bindingIndex() >= match.bindings().size())
      throw Problem.bad("INVALID_BINDING", "Choose an existing binding index");
    Binding binding = match.bindings().get(request.bindingIndex());
    ProfileInput profile = profiles.get(match.profileId()).snapshot();
    Set<String> evidenceIds = new HashSet<>();
    profile.evidence().forEach(e -> evidenceIds.add(e.id()));
    if (!evidenceIds.containsAll(request.catalog().evidenceIds()))
      throw Problem.bad("MISSING_EVIDENCE", "Catalog references unknown profile evidence");
    List<Blocker> blockers = new ArrayList<>();
    if (binding.applicability() == Truth.FALSE)
      blockers.add(
          new Blocker("NOT_APPLICABLE", "Necessary conditions are false for this binding"));
    else if (binding.applicability() == Truth.UNKNOWN
        || binding.defectConditions() == Truth.UNKNOWN)
      blockers.add(
          new Blocker(
              "EVIDENCE_UNAVAILABLE",
              "Confirm unknown role, applicability and defect conditions before an experiment"));
    Map<String, Parameter> parameters = new LinkedHashMap<>();
    Map<String, String> targets = new LinkedHashMap<>();
    List<String> executors = new ArrayList<>(List.of("recovery.explicit"));
    List<Observation> observations = new ArrayList<>();
    String faultType, injectPoint;
    if (match.templateId().equals("DT-DUALWRITE-LOST-001")) {
      if (!request.strategy().equals("PRECISE"))
        throw Problem.bad(
            "INVALID_STRATEGY", "T11 requires an explicitly selected producer-to-broker edge");
      faultType = "network_loss";
      injectPoint = "on_request";
      targets.put("source", binding.roles().get("producer"));
      targets.put("destination", binding.roles().get("broker"));
      executors.add("network_loss.edge");
      parameters.put(
          "loss", new Parameter(100, "percent", List.of(), "Original template: 100% loss"));
      Long budget =
          positiveFact(profile, binding.roles().get("producer"), "publish.totalBudgetMs", blockers);
      if (budget != null) {
        if (budget > Long.MAX_VALUE / 2)
          blockers.add(new Blocker("PARAMETER_UNRESOLVED", "Total budget overflows a duration"));
        else
          parameters.put(
              "duration",
              parameter(
                  budget * 2,
                  "ms",
                  profile,
                  binding.roles().get("producer"),
                  "publish.totalBudgetMs",
                  "2 * confirmed total attempt/backoff/deadline budget; versioned interpretation of"
                      + " the relative template parameter"));
      }
      Long wait =
          positiveFact(
              profile, binding.roles().get("producer"), "downstreamRecoveryBoundMs", blockers);
      if (wait != null)
        parameters.put(
            "recoveryWait",
            parameter(
                wait,
                "ms",
                profile,
                binding.roles().get("producer"),
                "downstreamRecoveryBoundMs",
                "Explicit delivery and downstream recovery observation bound"));
      observations.add(
          new Observation(
              "Exposure",
              "Actual loss on the bound edge AND successful business side effects during the fault",
              List.of("exposure.edge_loss", "business.side_effect")));
      observations.add(
          new Observation(
              "Activation",
              "Correlated producer publish failure for the same business keys; local success with"
                  + " ack=0 proves neither delivery nor failure",
              List.of("publish.failure.correlated")));
      observations.add(
          new Observation(
              "Consequence",
              "After recovery and the declared wait, committed keys still lack downstream records;"
                  + " finite-window claim only",
              List.of("downstream.record", "recovery.healthy")));
      observations.add(
          new Observation(
              "Protection",
              "Correlated durable retry/outbox/reconciliation completion, if configured",
              List.of("protection.delivery")));
      Fact acks = evaluator.fact(profile, binding.roles().get("producer"), "publish.acks");
      if (acks == null || acks.certainty() != Certainty.CONFIRMED || !validAcks(acks))
        blockers.add(
            new Blocker(
                "EVIDENCE_UNAVAILABLE",
                "Confirm effective publish.acks; ack=0 requires a correlated failure observer"));
    } else if (match.templateId().equals("DT-MQ-REDELIVER-001")) {
      faultType = "process_kill";
      injectPoint = "after_side_effect_before_commit";
      targets.put("consumer", binding.roles().get("consumer"));
      executors.add(
          request.strategy().equals("PRECISE")
              ? "process_kill.after_side_effect_before_commit"
              : "process_kill.probabilistic");
      parameters.put(
          "duration", new Parameter(300000, "ms", List.of(), "Original template: 5 minutes"));
      parameters.put(
          "interval", new Parameter(60000, "ms", List.of(), "Original template: once per minute"));
      observations.add(
          new Observation(
              "Exposure",
              "Restart/rebalance and actual workload reach the selected consumer",
              List.of("exposure.consumer_restart", "business.side_effect")));
      observations.add(
          new Observation(
              "Activation",
              "The SAME topic/partition/offset or stable business key is redelivered; restart alone"
                  + " is insufficient",
              List.of("message.redelivery.correlated")));
      observations.add(
          new Observation(
              "Consequence",
              "More than one effective business side effect per stable key",
              List.of("business.effect_count")));
      observations.add(
          new Observation(
              "Protection",
              "A recorded duplicate is rejected atomically with its side effect",
              List.of("protection.dedup")));
    } else throw Problem.bad("RULESET_NOT_IMPLEMENTED", "No plan compiler for this template");
    for (String capability : executors)
      if (!request.catalog().executorCapabilities().contains(capability))
        blockers.add(new Blocker("UNSUPPORTED", "Declared executor lacks " + capability));
    // Protection observations are conditional; they become necessary for an explicitly protected
    // static candidate.
    observations.stream()
        .filter(
            o -> !o.dimension().equals("Protection") || binding.defectConditions() == Truth.FALSE)
        .flatMap(o -> o.capabilities().stream())
        .distinct()
        .filter(c -> !request.catalog().observerCapabilities().contains(c))
        .forEach(
            c -> blockers.add(new Blocker("EVIDENCE_UNAVAILABLE", "Declared observer lacks " + c)));
    Parameter duration = parameters.get("duration");
    if (duration != null && duration.value() > request.limits().maxFaultDurationMs())
      blockers.add(
          new Blocker(
              "PARAMETER_UNRESOLVED",
              "Required fault duration exceeds the explicit limit; it was not silently clipped"));
    if (duration != null
        && request.workload().requests()
            < Math.ceil(duration.value() * request.workload().ratePerSecond() / 1000.0)) {
      blockers.add(
          new Blocker(
              "PARAMETER_UNRESOLVED",
              "WORKLOAD_WINDOW_TOO_SHORT: request count and rate cannot cover the planned fault"
                  + " window"));
    }
    if (request.workload().requests() > request.limits().maxRequests()
        || request.workload().ratePerSecond() > request.limits().maxRatePerSecond())
      blockers.add(
          new Blocker("PARAMETER_UNRESOLVED", "Workload exceeds the explicit experimental limits"));
    if (!Double.isFinite(request.workload().ratePerSecond())
        || !Double.isFinite(request.limits().maxRatePerSecond()))
      throw Problem.bad("INVALID_WORKLOAD", "Rates must be finite");
    boolean reviewComplete = blockers.isEmpty();
    // This increment deliberately has no dispatch endpoint or invented ChaosBlade CR.
    blockers.add(
        new Blocker(
            "EXECUTION_ADAPTER_NOT_IMPLEMENTED",
            "Abstract review plan only: no validated runtime selectors, ChaosBlade dictionary,"
                + " observation adapters or fault dispatch integration yet"));
    String readiness =
        blockers.stream().anyMatch(b -> b.code().equals("NOT_APPLICABLE"))
            ? "NOT_APPLICABLE"
            : blockers.stream().anyMatch(b -> b.code().equals("EVIDENCE_UNAVAILABLE"))
                ? "EVIDENCE_UNAVAILABLE"
                : blockers.stream().anyMatch(b -> b.code().equals("PARAMETER_UNRESOLVED"))
                    ? "PARAMETER_UNRESOLVED"
                    : "UNSUPPORTED";
    Plan plan =
        new Plan(
            UUID.randomUUID().toString(),
            Instant.now().toString(),
            match.id(),
            request.bindingIndex(),
            match.templateHash(),
            match.rulesetVersion(),
            match.profileId(),
            match.environment(),
            readiness,
            reviewComplete,
            false,
            List.copyOf(blockers),
            faultType,
            Map.copyOf(targets),
            injectPoint,
            request.strategy(),
            Map.copyOf(parameters),
            request.workload(),
            request.limits(),
            request.catalog(),
            List.copyOf(observations),
            List.of(
                "Preflight: pin deployment and evidence versions, validate selectors, bounds and"
                    + " recovery capability",
                "Baseline: confirm healthy traffic, stable business keys and complete observation"
                    + " coverage",
                "Inject: apply the supported real fault and verify target exposure",
                "Exercise: run bounded real workload during the fault, record business keys and"
                    + " timing",
                "Recover: remove fault in finally and verify cleanup independently; TTL is a"
                    + " fallback",
                "Observe recovery: wait the explicit bound; verify consumers and observers healthy",
                "Evaluate Exposure, Activation, Consequence and Protection separately; missing"
                    + " coverage yields UNKNOWN"),
            "NOT_EVALUATED: a static candidate and an abstract plan do not confirm a defect");
    store.put("plans", plan.id(), plan);
    return plan;
  }

  private boolean validAcks(Fact fact) {
    return (fact.value().isIntegralNumber()
            && fact.value().canConvertToInt()
            && Set.of(-1, 0, 1).contains(fact.value().intValue()))
        || (fact.value().isTextual()
            && Set.of("none", "leader", "all").contains(fact.value().textValue()));
  }

  private Long positiveFact(
      ProfileInput profile, String subject, String key, List<Blocker> blockers) {
    Fact fact = evaluator.fact(profile, subject, key);
    if (fact == null
        || fact.certainty() != Certainty.CONFIRMED
        || !fact.value().isIntegralNumber()
        || !fact.value().canConvertToLong()
        || fact.value().longValue() <= 0) {
      blockers.add(
          new Blocker(
              "PARAMETER_UNRESOLVED",
              "Need confirmed positive integer " + subject + "." + key + " in milliseconds"));
      return null;
    }
    return fact.value().longValue();
  }

  private Parameter parameter(
      long value, String unit, ProfileInput p, String subject, String key, String derivation) {
    return new Parameter(value, unit, evaluator.fact(p, subject, key).evidenceIds(), derivation);
  }

  public Plan get(String id) {
    return store.get("plans", id, Plan.class);
  }
}
