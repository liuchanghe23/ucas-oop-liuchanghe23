package com.chaosblade.transfer;

import static com.chaosblade.transfer.domain.Model.*;
import static org.junit.jupiter.api.Assertions.*;

import com.chaosblade.transfer.matching.PredicateEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class PredicateEvaluatorTest {
  final ObjectMapper mapper = new ObjectMapper();
  final PredicateEvaluator evaluator = new PredicateEvaluator(mapper);

  @ParameterizedTest
  @MethodSource("truthTable")
  void threeValuedBooleanRules(Truth a, Truth b, Truth all, Truth any) {
    assertEquals(all, Truth.all(List.of(a, b)));
    assertEquals(any, Truth.any(List.of(a, b)));
    assertEquals(a, a.not().not());
  }

  static Stream<Arguments> truthTable() {
    return Stream.of(
        Arguments.of(Truth.TRUE, Truth.TRUE, Truth.TRUE, Truth.TRUE),
        Arguments.of(Truth.TRUE, Truth.FALSE, Truth.FALSE, Truth.TRUE),
        Arguments.of(Truth.TRUE, Truth.UNKNOWN, Truth.UNKNOWN, Truth.TRUE),
        Arguments.of(Truth.FALSE, Truth.TRUE, Truth.FALSE, Truth.TRUE),
        Arguments.of(Truth.FALSE, Truth.FALSE, Truth.FALSE, Truth.FALSE),
        Arguments.of(Truth.FALSE, Truth.UNKNOWN, Truth.FALSE, Truth.UNKNOWN),
        Arguments.of(Truth.UNKNOWN, Truth.TRUE, Truth.UNKNOWN, Truth.TRUE),
        Arguments.of(Truth.UNKNOWN, Truth.FALSE, Truth.FALSE, Truth.UNKNOWN),
        Arguments.of(Truth.UNKNOWN, Truth.UNKNOWN, Truth.UNKNOWN, Truth.UNKNOWN));
  }

  @Test
  void missingOrInferredEvidenceNeverBecomesFalse() {
    ProfileInput p =
        new ProfileInput(
            "s",
            "v",
            "SYNTHETIC",
            List.of(
                new Entity(
                    "c",
                    "service",
                    Map.of(
                        "dedup.present",
                        new Fact(mapper.valueToTree(false), Certainty.INFERRED, List.of("e"))))),
            List.of(),
            Map.of(),
            List.of());
    assertEquals(Truth.UNKNOWN, evaluator.equalsFact(p, "c", "dedup.present", false).truth());
    assertEquals(Truth.UNKNOWN, evaluator.equalsFact(p, "c", "capability.dedup", true).truth());
    assertEquals(Truth.UNKNOWN, evaluator.relation(p, "b", "c", "DELIVERS").truth());
  }

  @Test
  void wrongTypeIsUnknownRatherThanAnIncorrectNegative() {
    ProfileInput p =
        new ProfileInput(
            "s",
            "v",
            "SYNTHETIC",
            List.of(
                new Entity(
                    "c",
                    "service",
                    Map.of(
                        "dedup.present",
                        new Fact(mapper.valueToTree("false"), Certainty.CONFIRMED, List.of("e"))))),
            List.of(),
            Map.of(),
            List.of());
    assertEquals(Truth.UNKNOWN, evaluator.equalsFact(p, "c", "dedup.present", false).truth());
  }
}
