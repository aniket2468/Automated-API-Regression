package dev.apiguard;

import static dev.apiguard.Models.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlatformStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public PlatformStore(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void saveSpec(Specification s) {
    jdbc.update(
        "INSERT INTO specifications VALUES (?,?,?,?)",
        s.id(),
        s.name(),
        s.createdAt().toString(),
        encode(s));
  }

  public Specification spec(String id) {
    return one("specifications", id, Specification.class);
  }

  public List<Specification> specs() {
    return list("SELECT payload FROM specifications ORDER BY created_at DESC", Specification.class);
  }

  public void saveSuite(Suite s) {
    jdbc.update(
        "INSERT INTO suites VALUES (?,?,?,?)",
        s.id(),
        s.specId(),
        s.createdAt().toString(),
        encode(s));
  }

  public void updateSuite(Suite s) {
    jdbc.update("UPDATE suites SET payload=? WHERE id=?", encode(s), s.id());
  }

  public Suite suite(String id) {
    return one("suites", id, Suite.class);
  }

  public List<Suite> suites() {
    return list("SELECT payload FROM suites ORDER BY created_at DESC", Suite.class);
  }

  public void saveRun(Run r) {
    jdbc.update(
        "INSERT INTO runs VALUES (?,?,?,?,?)",
        r.id(),
        r.suiteId(),
        r.status(),
        r.createdAt().toString(),
        encode(r));
  }

  public void updateRun(Run r) {
    jdbc.update("UPDATE runs SET status=?,payload=? WHERE id=?", r.status(), encode(r), r.id());
  }

  public Run run(String id) {
    return one("runs", id, Run.class);
  }

  public List<Run> runs(String suiteId, int limit) {
    return suiteId == null
        ? list("SELECT payload FROM runs ORDER BY created_at DESC LIMIT ?", Run.class, limit)
        : list(
            "SELECT payload FROM runs WHERE suite_id=? ORDER BY created_at DESC LIMIT ?",
            Run.class,
            suiteId,
            limit);
  }

  public List<Run> unfinishedRuns() {
    return list("SELECT payload FROM runs WHERE status IN ('QUEUED','RUNNING')", Run.class);
  }

  public void saveResult(String runId, TestResult r) {
    jdbc.update("INSERT INTO test_results VALUES (?,?,?)", runId, r.testId(), encode(r));
  }

  public List<TestResult> results(String runId) {
    return list(
        "SELECT payload FROM test_results WHERE run_id=? ORDER BY test_id",
        TestResult.class,
        runId);
  }

  private <T> T one(String table, String id, Class<T> type) {
    var rows = list("SELECT payload FROM " + table + " WHERE id=?", type, id);
    if (rows.isEmpty()) throw ApiException.notFound(table, id);
    return rows.get(0);
  }

  private <T> List<T> list(String sql, Class<T> type, Object... args) {
    return jdbc.query(sql, (rs, row) -> decode(rs.getString(1), type), args);
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize persisted data", e);
    }
  }

  private <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot read persisted data", e);
    }
  }
}
