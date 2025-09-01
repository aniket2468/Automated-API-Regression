package dev.apiguard;

import static dev.apiguard.Models.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SuiteService {
  private final PlatformStore store;
  private final SpecEngine engine;
  private final ObjectMapper json;

  public SuiteService(PlatformStore store, SpecEngine engine, ObjectMapper json) {
    this.store = store;
    this.engine = engine;
    this.json = json;
  }

  public Suite generate(String specId, String name) {
    Specification spec = store.spec(specId);
    if (name == null || name.isBlank() || name.length() > 160)
      throw ApiException.badRequest("Suite name must contain 1–160 characters");
    List<TestCase> tests = new ArrayList<>();
    engine
        .operations(spec.document())
        .forEach((key, op) -> tests.add(generateCase(key, op, spec.document())));
    Suite suite =
        new Suite(
            UUID.randomUUID().toString(), name.trim(), specId, Instant.now(), List.copyOf(tests));
    store.saveSuite(suite);
    return suite;
  }

  private TestCase generateCase(String key, JsonNode op, JsonNode root) {
    String method = key.substring(0, key.indexOf(' ')), path = key.substring(key.indexOf(' ') + 1);
    List<String> reasons = new ArrayList<>();
    if (!Set.of("GET", "HEAD", "OPTIONS").contains(method))
      reasons.add("Review inputs before enabling a write operation");
    List<Integer> successes = new ArrayList<>();
    op.path("responses")
        .fieldNames()
        .forEachRemaining(
            code -> {
              if (code.matches("2[0-9][0-9]")) successes.add(Integer.parseInt(code));
            });
    Collections.sort(successes);
    int expected = successes.isEmpty() ? 200 : successes.get(0);
    if (successes.isEmpty())
      reasons.add("An explicit 2xx response is required for automatic generation");
    JsonNode response =
        engine.dereference(op.path("responses").path(String.valueOf(expected)), root);
    String mediaType = selectJsonMedia(response.path("content"));
    if (response.path("content").size() > 0 && mediaType == null)
      reasons.add("Only JSON response content is supported");
    JsonNode schema =
        mediaType == null
            ? null
            : engine.responseSchema(response.path("content").path(mediaType).path("schema"), root);
    List<String> responseHeaders = new ArrayList<>();
    response.path("headers").fieldNames().forEachRemaining(responseHeaders::add);
    Map<String, String> pathParams = new LinkedHashMap<>(),
        query = new LinkedHashMap<>(),
        headers = new LinkedHashMap<>();
    for (JsonNode raw : op.path("parameters")) {
      JsonNode p = engine.dereference(raw, root);
      if (!p.path("required").asBoolean() && !p.has("example")) continue;
      String in = p.path("in").asText(), name = p.path("name").asText();
      JsonNode value =
          p.has("example") ? p.get("example") : sample(p.path("schema"), root, new HashSet<>(), 0);
      if (value == null || !value.isValueNode()) {
        reasons.add("Supply a scalar value for parameter " + name);
        continue;
      }
      switch (in) {
        case "path" -> pathParams.put(name, value.asText());
        case "query" -> query.put(name, value.asText());
        case "header" -> headers.put(name, value.asText());
        default -> reasons.add("Cookie parameter needs manual setup: " + name);
      }
    }
    JsonNode body = null;
    if (op.has("requestBody")) {
      JsonNode request = engine.dereference(op.get("requestBody"), root);
      String requestType = selectJsonMedia(request.path("content"));
      if (requestType == null) reasons.add("Only JSON request bodies are supported");
      else {
        JsonNode content = request.path("content").path(requestType);
        body =
            content.has("example")
                ? content.get("example")
                : sample(content.path("schema"), root, new HashSet<>(), 0);
        headers.put("Content-Type", requestType);
        if (body == null && request.path("required").asBoolean())
          reasons.add("Supply a request body");
      }
    }
    if (!op.path("security").isEmpty()) {
      boolean optional = false;
      for (JsonNode security : op.path("security")) if (security.isEmpty()) optional = true;
      if (!optional) reasons.add("Supply authentication headers before enabling");
    }
    String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    TestConfig config =
        new TestConfig(
            reasons.isEmpty(),
            String.join("; ", reasons),
            pathParams,
            query,
            headers,
            body,
            Map.of(),
            List.of(),
            2000);
    return new TestCase(
        id, key, method, path, expected, mediaType, schema, List.copyOf(responseHeaders), config);
  }

  private String selectJsonMedia(JsonNode content) {
    if (content.has("application/json")) return "application/json";
    List<String> types = new ArrayList<>();
    content.fieldNames().forEachRemaining(types::add);
    return types.stream()
        .filter(s -> s.endsWith("+json") && !s.contains("*"))
        .sorted()
        .findFirst()
        .orElse(null);
  }

  private JsonNode sample(JsonNode raw, JsonNode root, Set<String> refs, int depth) {
    if (depth > 8 || raw.isMissingNode()) return null;
    if (raw.has("$ref")) {
      String ref = raw.path("$ref").asText();
      if (!refs.add(ref)) return null;
      return sample(root.at(ref.substring(1)), root, refs, depth + 1);
    }
    if (raw.has("example")) return raw.get("example");
    if (raw.has("default")) return raw.get("default");
    if (raw.path("enum").size() > 0) return raw.path("enum").get(0);
    return switch (raw.path("type").asText()) {
      case "integer", "number" ->
          raw.has("minimum") ? raw.get("minimum") : json.getNodeFactory().numberNode(1);
      case "boolean" -> json.getNodeFactory().booleanNode(true);
      case "string" ->
          json.getNodeFactory()
              .textNode(
                  switch (raw.path("format").asText()) {
                    case "date" -> "2025-01-01";
                    case "date-time" -> "2025-01-01T00:00:00Z";
                    case "uuid" -> "00000000-0000-0000-0000-000000000001";
                    default -> "sample";
                  });
      case "array" -> {
        JsonNode item = sample(raw.path("items"), root, new HashSet<>(refs), depth + 1);
        yield item == null ? null : json.createArrayNode().add(item);
      }
      case "object" -> {
        var object = json.createObjectNode();
        for (JsonNode field : raw.path("required")) {
          JsonNode value =
              sample(
                  raw.path("properties").path(field.asText()),
                  root,
                  new HashSet<>(refs),
                  depth + 1);
          if (value != null) object.set(field.asText(), value);
        }
        yield object;
      }
      default -> raw.path("nullable").asBoolean() ? NullNode.instance : null;
    };
  }

  public synchronized Suite configure(String suiteId, String testId, TestConfig config) {
    validate(config);
    Suite suite = store.suite(suiteId);
    if (suite.tests().stream().noneMatch(t -> t.id().equals(testId)))
      throw ApiException.notFound("Test", testId);
    List<TestCase> tests =
        suite.tests().stream()
            .map(
                t ->
                    t.id().equals(testId)
                        ? new TestCase(
                            t.id(),
                            t.operation(),
                            t.method(),
                            t.path(),
                            t.expectedStatus(),
                            t.responseMediaType(),
                            t.responseSchema(),
                            t.responseHeaders(),
                            config)
                        : t)
            .toList();
    Suite updated = new Suite(suite.id(), suite.name(), suite.specId(), suite.createdAt(), tests);
    store.updateSuite(updated);
    return updated;
  }

  private void validate(TestConfig c) {
    if (c == null
        || c.pathParams() == null
        || c.queryParams() == null
        || c.requestHeaders() == null
        || c.expectedHeaders() == null
        || c.assertions() == null)
      throw ApiException.badRequest(
          "Provide enabled, pathParams, queryParams, requestHeaders, expectedHeaders, assertions and maxLatencyMs");
    if (c.maxLatencyMs() < 1 || c.maxLatencyMs() > 120_000)
      throw ApiException.badRequest("maxLatencyMs must be between 1 and 120000");
    for (Map<String, String> values :
        List.of(c.pathParams(), c.queryParams(), c.requestHeaders(), c.expectedHeaders()))
      values.forEach(
          (key, value) -> {
            if (key == null
                || key.isBlank()
                || value == null
                || key.contains("\r")
                || key.contains("\n")
                || value.contains("\r")
                || value.contains("\n"))
              throw ApiException.badRequest(
                  "Parameter and header values must be non-null and single-line");
          });
    if (c.requestHeaders().keySet().stream()
        .anyMatch(
            k ->
                Set.of("host", "content-length", "transfer-encoding", "connection")
                    .contains(k.toLowerCase(Locale.ROOT))))
      throw ApiException.badRequest("Host and transport headers are managed by the runner");
    for (Assertion a : c.assertions()) {
      if (a == null || a.pointer() == null || a.expected() == null)
        throw ApiException.badRequest("Each assertion needs a JSON pointer and expected value");
      try {
        com.fasterxml.jackson.core.JsonPointer.compile(a.pointer());
      } catch (IllegalArgumentException e) {
        throw ApiException.badRequest("Invalid JSON pointer: " + a.pointer());
      }
    }
  }
}
