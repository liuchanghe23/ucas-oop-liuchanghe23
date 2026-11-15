package com.chaosblade.transfer;

import static com.chaosblade.transfer.domain.Model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.chaosblade.transfer.matching.*;
import com.chaosblade.transfer.planning.PlanningService;
import com.chaosblade.transfer.profile.*;
import com.chaosblade.transfer.template.TemplateService;
import com.chaosblade.transfer.web.Problem;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransferFlowTest {
  static final Path STORE = tempStore();

  static Path tempStore() {
    try {
      return Files.createTempDirectory("transfer-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("transfer.store-dir", STORE::toString);
  }

  @Autowired TemplateService templates;
  @Autowired ProfileService profiles;
  @Autowired MatchingService matching;
  @Autowired PlanningService planning;
  @Autowired SourceHintService source;
  @Autowired RuleRegistry registry;
  @Autowired ObjectMapper mapper;
  @Autowired MockMvc mvc;

  JsonNode fixture(String name) throws Exception {
    return mapper.readTree(new ClassPathResource(name).getInputStream());
  }

  Profile profile(String name) throws Exception {
    return profiles.create(mapper.treeToValue(fixture(name), ProfileInput.class));
  }

  Profile profile(JsonNode node) throws Exception {
    return profiles.create(mapper.treeToValue(node, ProfileInput.class));
  }

  Match match(Profile p, String template) {
    return matching.match(new MatchRequest(template, 1, p.id(), 128));
  }

  @BeforeEach
  void importTemplates() throws Exception {
    templates.importMarkdown(
        new ClassPathResource("chaosblade_defect_templates.md")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
  }

  PlanRequest request(Match match, String strategy) throws Exception {
    ObjectNode node = (ObjectNode) fixture("plan-request.json");
    node.put("matchId", match.id());
    node.put("strategy", strategy);
    if (match.templateId().equals("DT-MQ-REDELIVER-001")) {
      ((ObjectNode) node.path("workload")).put("requests", 600);
      ((ObjectNode) node.path("limits")).put("maxRequests", 1000);
    }
    return mapper.treeToValue(node, PlanRequest.class);
  }

  @Test
  void t11SyntheticFlowIsAStaticCandidateNotAClaimedDefect() throws Exception {
    Match m = match(profile("t11-synthetic.json"), "DT-DUALWRITE-LOST-001");
    assertEquals("APPLICABLE", m.suitability());
    assertTrue(m.enumerationComplete());
    assertEquals(1, m.bindings().size());
    assertEquals(Truth.TRUE, m.bindings().getFirst().defectConditions());
    assertEquals("writer", m.bindings().getFirst().roles().get("producer"));
    Plan p = planning.plan(request(m, "PRECISE"));
    assertEquals(5000, p.parameters().get("duration").value());
    assertEquals("SYNTHETIC", p.environment());
    assertEquals("UNSUPPORTED", p.readiness());
    assertTrue(p.reviewInputsComplete());
    assertFalse(p.executionEnabled());
    assertTrue(p.conclusion().startsWith("NOT_EVALUATED"));
    assertEquals(p, planning.get(p.id()));
  }

  @Test
  void optionalGuardAbsentIsNotProofOfNoDedup() throws Exception {
    ObjectNode node = (ObjectNode) fixture("t10-protected-synthetic.json");
    ((com.fasterxml.jackson.databind.node.ArrayNode) node.path("entities")).remove(2);
    ((com.fasterxml.jackson.databind.node.ArrayNode) node.path("edges")).remove(1);
    ((ObjectNode) node.path("entities").get(1).path("facts")).remove("dedup.present");
    Match m = match(profile(node), "DT-MQ-REDELIVER-001");
    assertEquals(Truth.UNKNOWN, m.bindings().getFirst().defectConditions());
    Condition d1 =
        m.bindings().getFirst().conditions().stream()
            .filter(c -> c.id().equals("D1"))
            .findFirst()
            .orElseThrow();
    assertEquals(Truth.UNKNOWN, d1.result().truth());
    assertEquals("EVIDENCE_UNAVAILABLE", planning.plan(request(m, "PROBABILISTIC")).readiness());
  }

  @Test
  void t10ProtectedCandidateRemainsVisibleAndProbabilisticStrategyExplicit() throws Exception {
    Match m = match(profile("t10-protected-synthetic.json"), "DT-MQ-REDELIVER-001");
    assertEquals("APPLICABLE", m.suitability());
    assertTrue(m.bindings().stream().allMatch(b -> b.defectConditions() == Truth.FALSE));
    Plan p = planning.plan(request(m, "PROBABILISTIC"));
    assertEquals("PROBABILISTIC", p.strategy());
    assertTrue(p.reviewInputsComplete());
    assertTrue(p.observations().stream().anyMatch(o -> o.rule().contains("redelivered")));
    assertFalse(p.executionEnabled());
  }

  @Test
  void noEvidenceForAbsenceAndNoOperationalSpecAreExplicit() throws Exception {
    ObjectNode node = (ObjectNode) fixture("t11-synthetic.json");
    ((ObjectNode) node.path("entities").get(0).path("facts")).remove("outbox.present");
    Match m = match(profile(node), "DT-DUALWRITE-LOST-001");
    assertEquals(Truth.UNKNOWN, m.bindings().getFirst().defectConditions());
    Match other = match(profile("t11-synthetic.json"), "DT-RETRY-DUP-001");
    assertTrue(other.bindings().isEmpty());
    assertEquals("UNKNOWN", other.suitability());
    assertTrue(other.limitations().stream().anyMatch(x -> x.startsWith("RULESET_NOT_IMPLEMENTED")));
  }

  @Test
  void changedTemplateCannotSilentlyReuseRules() {
    var t = templates.get("DT-DUALWRITE-LOST-001", 1);
    assertNull(
        registry.find(
            new Template(
                t.templateId(),
                2,
                t.status(),
                t.sourceHash(),
                t.importedAt(),
                t.yaml(),
                t.document())));
    assertNull(
        registry.find(
            new Template(
                t.templateId(),
                1,
                t.status(),
                "different",
                t.importedAt(),
                t.yaml(),
                t.document())));
    assertNotNull(registry.find(t));
  }

  @Test
  void noMatchForOneBindingDoesNotExcludeAnotherBinding() throws Exception {
    ObjectNode node = (ObjectNode) fixture("t11-synthetic.json");
    ObjectNode bad = ((ObjectNode) node.path("entities").get(0)).deepCopy();
    bad.put("id", "a-bad-writer");
    ((ObjectNode) bad.path("facts").path("sideEffectBeforePublish")).put("value", false);
    ((com.fasterxml.jackson.databind.node.ArrayNode) node.path("entities")).add(bad);
    var edges = (com.fasterxml.jackson.databind.node.ArrayNode) node.path("edges");
    ObjectNode edge = ((ObjectNode) edges.get(0)).deepCopy();
    edge.put("from", "a-bad-writer");
    edges.add(edge);
    Match m = match(profile(node), "DT-DUALWRITE-LOST-001");
    assertEquals("APPLICABLE", m.suitability());
    assertTrue(m.bindings().stream().anyMatch(b -> b.applicability() == Truth.FALSE));
  }

  @Test
  void enumerationLimitIsNeverReportedAsACompleteNegative() throws Exception {
    ObjectNode node = (ObjectNode) fixture("t11-synthetic.json");
    node.set("facts", mapper.createObjectNode());
    Profile p = profile(node);
    Match m = matching.match(new MatchRequest("DT-DUALWRITE-LOST-001", 1, p.id(), 1));
    assertFalse(m.enumerationComplete());
    assertTrue(m.limitations().stream().anyMatch(x -> x.startsWith("ENUMERATION_TRUNCATED")));
  }

  @Test
  void retriesZeroDoesNotProduceZeroDurationAndMissingBudgetDoesNotUseDefaults() throws Exception {
    ObjectNode node = (ObjectNode) fixture("t11-synthetic.json");
    ((ObjectNode) node.path("entities").get(0).path("facts")).remove("publish.totalBudgetMs");
    Plan p = planning.plan(request(match(profile(node), "DT-DUALWRITE-LOST-001"), "PRECISE"));
    assertEquals("PARAMETER_UNRESOLVED", p.readiness());
    assertFalse(p.parameters().containsKey("duration"));
  }

  @Test
  void durationAboveLimitIsReportedWithoutClipping() throws Exception {
    Match m = match(profile("t11-synthetic.json"), "DT-DUALWRITE-LOST-001");
    PlanRequest r = request(m, "PRECISE");
    Plan p =
        planning.plan(
            new PlanRequest(
                r.matchId(), 0, r.strategy(), r.catalog(), r.workload(), new Limits(1000, 5, 50)));
    assertEquals("PARAMETER_UNRESOLVED", p.readiness());
    assertEquals(5000, p.parameters().get("duration").value());
  }

  @Test
  void ackZeroCannotSubstituteLocalSuccessForCorrelatedFailure() throws Exception {
    ObjectNode node = (ObjectNode) fixture("t11-synthetic.json");
    ((ObjectNode) node.path("entities").get(0).path("facts").path("publish.acks")).put("value", 0);
    Match m = match(profile(node), "DT-DUALWRITE-LOST-001");
    PlanRequest r = request(m, "PRECISE");
    Set<String> observers = new HashSet<>(r.catalog().observerCapabilities());
    observers.remove("publish.failure.correlated");
    Catalog c =
        new Catalog(
            "ack0", "v1", r.catalog().executorCapabilities(), observers, r.catalog().evidenceIds());
    Plan p = planning.plan(new PlanRequest(m.id(), 0, "PRECISE", c, r.workload(), r.limits()));
    assertEquals("EVIDENCE_UNAVAILABLE", p.readiness());
  }

  @Test
  void missingExecutionCapabilityIsReported() throws Exception {
    Match m = match(profile("t10-protected-synthetic.json"), "DT-MQ-REDELIVER-001");
    PlanRequest r = request(m, "PRECISE");
    Catalog c =
        new Catalog(
            "unsupported",
            "v1",
            Set.of(),
            r.catalog().observerCapabilities(),
            r.catalog().evidenceIds());
    Plan p = planning.plan(new PlanRequest(m.id(), 0, "PRECISE", c, r.workload(), r.limits()));
    assertFalse(p.reviewInputsComplete());
    assertTrue(
        p.blockers().stream()
            .anyMatch(b -> b.detail().contains("process_kill.after_side_effect_before_commit")));
  }

  @Test
  void missingEvidenceAndSyntheticMislabelAreRejected() throws Exception {
    ObjectNode node = (ObjectNode) fixture("t11-synthetic.json");
    node.set("evidence", mapper.createArrayNode());
    assertEquals("MISSING_EVIDENCE", assertThrows(Problem.class, () -> profile(node)).code());
    ObjectNode wrong = (ObjectNode) fixture("t11-synthetic.json");
    wrong.put("environment", "DEPLOYMENT");
    assertEquals("SYNTHETIC_EVIDENCE", assertThrows(Problem.class, () -> profile(wrong)).code());
  }

  @Test
  void sourceAdaptersOnlyYieldHintsWithLineEvidence() {
    var result =
        source.extract(
            new SourceHintService.Request(
                "real-source-snippets",
                "commit123",
                List.of(
                    new SourceHintService.Snippet(
                        "writer",
                        "producer.go",
                        "go",
                        "// x.RequiredAcks = sarama.NoResponse\n"
                            + "x.RequiredAcks = sarama.NoResponse"),
                    new SourceHintService.Snippet(
                        "reader",
                        "Consumer.cs",
                        "dotnet",
                        "EnableAutoCommit = true;\n"
                            + "db.SaveChanges();\n"
                            + "catch (PostgresErrorCodes.UniqueViolation) {}"))));
    var p = result.profile().snapshot();
    assertTrue(p.edges().isEmpty());
    assertEquals("SOURCE_SNAPSHOT", p.environment());
    assertTrue(
        p.entities().stream()
            .flatMap(e -> e.facts().values().stream())
            .allMatch(f -> f.certainty() == Certainty.INFERRED));
    assertEquals("producer.go:2", p.evidence().getFirst().locator());
    assertFalse(p.entities().get(1).facts().containsKey("dedup.atomicWithEffect"));
  }

  @Test
  void invalidApiRequestsReturn400AndSnapshotsAreRetrievable() throws Exception {
    mvc.perform(
            post("/api/defect-transfer/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    ObjectNode node = (ObjectNode) fixture("t11-synthetic.json");
    ((ObjectNode) node.path("entities").get(0).path("facts")).putNull("capability.publish");
    mvc.perform(
            post("/api/defect-transfer/profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(node)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/defect-transfer/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ENDPOINT_NOT_FOUND"));
    mvc.perform(get("/api/defect-transfer/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.executionEnabled").value(false));
    var p = profile("t11-synthetic.json");
    mvc.perform(get("/api/defect-transfer/profiles/" + p.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(p.id()));
    mvc.perform(get("/api/defect-transfer/profiles/missing")).andExpect(status().isNotFound());
  }

  @Test
  void shortWorkloadCannotClaimToCoverFiveMinuteFault() throws Exception {
    Match m = match(profile("t10-protected-synthetic.json"), "DT-MQ-REDELIVER-001");
    PlanRequest r = request(m, "PROBABILISTIC");
    Plan p =
        planning.plan(
            new PlanRequest(
                m.id(),
                0,
                r.strategy(),
                r.catalog(),
                new Workload("too short", "orderId", 2, 10),
                r.limits()));
    assertFalse(p.reviewInputsComplete());
    assertTrue(
        p.blockers().stream().anyMatch(b -> b.detail().startsWith("WORKLOAD_WINDOW_TOO_SHORT")));
  }

  @Test
  void contradictoryGuardEvidenceStaysUnknown() throws Exception {
    ObjectNode node = (ObjectNode) fixture("t10-protected-synthetic.json");
    ((ObjectNode) node.path("entities").get(1).path("facts").path("dedup.present"))
        .put("value", false);
    Match m = match(profile(node), "DT-MQ-REDELIVER-001");
    assertTrue(m.bindings().stream().allMatch(b -> b.defectConditions() == Truth.UNKNOWN));
    Binding withGuard =
        m.bindings().stream().filter(b -> b.roles().containsKey("guard")).findFirst().orElseThrow();
    assertEquals(Truth.UNKNOWN, withGuard.defectConditions());
    assertTrue(
        withGuard.conditions().stream()
            .flatMap(c -> c.result().missing().stream())
            .anyMatch(x -> x.startsWith("INCONSISTENT_GUARD_EVIDENCE")));
  }

  @Test
  void globalNotApplicableNeedsCoverageAndAllBindingsExcluded() throws Exception {
    ObjectNode node = (ObjectNode) fixture("t11-synthetic.json");
    ((ObjectNode) node.path("entities").get(0).path("facts").path("sideEffectBeforePublish"))
        .put("value", false);
    assertEquals("NOT_APPLICABLE", match(profile(node), "DT-DUALWRITE-LOST-001").suitability());
    ((ObjectNode) node.path("facts")).remove("inventory.complete");
    assertEquals("UNKNOWN", match(profile(node), "DT-DUALWRITE-LOST-001").suitability());
  }
}
