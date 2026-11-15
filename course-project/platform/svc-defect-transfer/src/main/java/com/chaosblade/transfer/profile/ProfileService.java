package com.chaosblade.transfer.profile;

import static com.chaosblade.transfer.domain.Model.*;

import com.chaosblade.transfer.store.SnapshotStore;
import com.chaosblade.transfer.web.Problem;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {
  private final SnapshotStore store;

  public ProfileService(SnapshotStore store) {
    this.store = store;
  }

  public Profile create(ProfileInput input) {
    Set<String> ids = new HashSet<>();
    for (Evidence e : input.evidence()) {
      if (!ids.add(e.id()))
        throw Problem.bad("DUPLICATE_EVIDENCE", "Repeated evidence ID: " + e.id());
      if (e.kind() == EvidenceKind.MANUAL && (e.reviewer() == null || e.reviewer().isBlank()))
        throw Problem.bad("MISSING_REVIEWER", "Manual evidence must name its reviewer");
      if (e.kind() == EvidenceKind.SYNTHETIC && !input.environment().equals("SYNTHETIC"))
        throw Problem.bad(
            "SYNTHETIC_EVIDENCE", "Synthetic evidence cannot be imported as a real target");
    }
    Set<String> entities = new HashSet<>();
    for (Entity entity : input.entities()) {
      if (!entities.add(entity.id()))
        throw Problem.bad("DUPLICATE_ENTITY", "Repeated entity ID: " + entity.id());
      entity.facts().forEach((key, fact) -> validateFact(key, fact, ids));
    }
    input.facts().forEach((key, fact) -> validateFact(key, fact, ids));
    Set<String> edgeKeys = new HashSet<>();
    for (Edge edge : input.edges()) {
      if (!entities.contains(edge.from()) || !entities.contains(edge.to()))
        throw Problem.bad("DANGLING_EDGE", "An edge references an unknown entity");
      if (!edgeKeys.add(edge.from() + "|" + edge.type() + "|" + edge.to()))
        throw Problem.bad("DUPLICATE_EDGE", "Merge evidence for a repeated edge");
      references(edge.evidenceIds(), ids);
    }
    Profile p = new Profile(UUID.randomUUID().toString(), Instant.now().toString(), input);
    store.put("profiles", p.id(), p);
    return p;
  }

  private void validateFact(String key, Fact fact, Set<String> ids) {
    if (key.isBlank() || fact.value().isNull() || !fact.value().isValueNode())
      throw Problem.bad("INVALID_FACT", "Facts must be named scalar values");
    references(fact.evidenceIds(), ids);
  }

  private void references(List<String> refs, Set<String> ids) {
    if (refs == null || refs.isEmpty() || !ids.containsAll(refs))
      throw Problem.bad("MISSING_EVIDENCE", "Every fact and edge must reference existing evidence");
  }

  public Profile get(String id) {
    return store.get("profiles", id, Profile.class);
  }
}
