package dev.apiguard;

import static dev.apiguard.Models.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ImpactService {
  private final PlatformStore store;
  private final SpecEngine engine;

  public ImpactService(PlatformStore store, SpecEngine engine) {
    this.store = store;
    this.engine = engine;
  }

  public Impact compare(String oldId, String newId, String suiteId) {
    Specification oldSpec = store.spec(oldId), newSpec = store.spec(newId);
    Suite suite = suiteId == null ? null : store.suite(suiteId);
    if (suite != null && !suite.specId().equals(oldId))
      throw ApiException.badRequest("The suite must belong to oldSpecId");
    var oldOps = engine.operations(oldSpec.document());
    var newOps = engine.operations(newSpec.document());
    Set<String> all = new TreeSet<>(oldOps.keySet());
    all.addAll(newOps.keySet());
    List<Change> changes = new ArrayList<>();
    List<String> added = new ArrayList<>();
    for (String key : all) {
      if (!oldOps.containsKey(key)) {
        changes.add(new Change(key, "ADDED", List.of("New operation")));
        added.add(key);
      } else if (!newOps.containsKey(key))
        changes.add(new Change(key, "REMOVED", List.of("Operation removed")));
      else {
        JsonNode before = engine.fingerprint(oldOps.get(key), oldSpec.document());
        JsonNode after = engine.fingerprint(newOps.get(key), newSpec.document());
        if (!before.equals(after)) {
          List<String> reasons = new ArrayList<>();
          Set<String> fields = new TreeSet<>();
          oldOps.get(key).fieldNames().forEachRemaining(fields::add);
          newOps.get(key).fieldNames().forEachRemaining(fields::add);
          for (String field : fields)
            if (!oldOps.get(key).path(field).equals(newOps.get(key).path(field)))
              reasons.add("Operation field changed: " + field);
          Set<String> refs = new TreeSet<>();
          before.path("references").fieldNames().forEachRemaining(refs::add);
          after.path("references").fieldNames().forEachRemaining(refs::add);
          for (String ref : refs)
            if (!before.path("references").path(ref).equals(after.path("references").path(ref)))
              reasons.add("Dependency changed: " + ref);
          changes.add(new Change(key, "MODIFIED", List.copyOf(reasons)));
        }
      }
    }
    Set<String> changed = new HashSet<>();
    changes.forEach(c -> changed.add(c.operation()));
    List<String> affected =
        suite == null
            ? List.of()
            : suite.tests().stream()
                .filter(t -> changed.contains(t.operation()))
                .map(TestCase::id)
                .toList();
    return new Impact(oldId, newId, List.copyOf(changes), affected, List.copyOf(added));
  }
}
