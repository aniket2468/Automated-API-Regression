package dev.apiguard;

import static dev.apiguard.Models.*;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SpecEngine {
  private static final Set<String> METHODS =
      Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
  private final ObjectMapper yaml =
      new ObjectMapper(
          YAMLFactory.builder()
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(100)
                      .maxStringLength(2_000_000)
                      .build())
              .build());
  private final ObjectMapper json;
  private final PlatformStore store;

  public SpecEngine(ObjectMapper json, PlatformStore store) {
    this.json = json;
    this.store = store;
  }

  public Specification importSpec(String name, String content) {
    if (name == null || name.isBlank() || name.length() > 160)
      throw ApiException.badRequest("Name must contain 1–160 characters");
    if (content == null || content.getBytes(StandardCharsets.UTF_8).length > 2_000_000)
      throw ApiException.badRequest("Specification must be at most 2 MB");
    JsonNode doc;
    try {
      doc = yaml.readTree(content);
    } catch (Exception e) {
      throw ApiException.badRequest("Specification is not valid JSON or YAML");
    }
    if (doc == null || !doc.isObject() || !doc.path("openapi").asText().matches("3\\.0\\.[0-9]+"))
      throw ApiException.badRequest(
          "Only OpenAPI 3.0.x is supported; convert Swagger 2 or OpenAPI 3.1 first");
    verifyReferences(doc, doc);
    var options = new ParseOptions();
    options.setResolve(false);
    options.setResolveFully(false);
    var parsed = new OpenAPIV3Parser().readContents(doc.toString(), null, options);
    if (parsed.getOpenAPI() == null
        || (parsed.getMessages() != null && !parsed.getMessages().isEmpty()))
      throw ApiException.badRequest(
          "Invalid OpenAPI specification: "
              + String.join(
                  "; ",
                  Optional.ofNullable(parsed.getMessages())
                      .orElse(List.of("Cannot parse document"))));
    var ops = operations(doc);
    if (ops.isEmpty())
      throw ApiException.badRequest("Specification must define at least one HTTP operation");
    if (ops.size() > 200)
      throw ApiException.badRequest("A specification can contain at most 200 operations");
    var spec =
        new Specification(
            UUID.randomUUID().toString(),
            name.trim(),
            doc.path("info").path("version").asText(),
            Instant.now(),
            doc,
            List.of());
    store.saveSpec(spec);
    return spec;
  }

  private void verifyReferences(JsonNode node, JsonNode root) {
    if (node.isObject()) {
      if (node.has("$id") || node.has("$schema"))
        throw ApiException.badRequest(
            "Custom JSON Schema dialects and $id scopes are not supported in OpenAPI 3.0 imports");
      if (node.has("$ref")) {
        String ref = node.path("$ref").asText();
        if (!ref.startsWith("#/"))
          throw ApiException.badRequest(
              "Only local $ref values are supported; bundle external references before importing");
        if (root.at(ref.substring(1)).isMissingNode())
          throw ApiException.badRequest("Unresolved reference: " + ref);
      }
    }
    if (node.isContainerNode()) node.forEach(child -> verifyReferences(child, root));
  }

  public JsonNode dereference(JsonNode node, JsonNode root) {
    Set<String> seen = new HashSet<>();
    while (node.has("$ref")) {
      String ref = node.path("$ref").asText();
      if (!seen.add(ref))
        throw ApiException.badRequest("Circular reference outside a schema: " + ref);
      node = root.at(ref.substring(1));
    }
    return node;
  }

  public SortedMap<String, JsonNode> operations(JsonNode doc) {
    SortedMap<String, JsonNode> result = new TreeMap<>();
    doc.path("paths")
        .fields()
        .forEachRemaining(
            path -> {
              if (!path.getKey().startsWith("/")
                  || path.getKey().startsWith("//")
                  || path.getKey().contains("\\")
                  || path.getKey().contains("?")
                  || path.getKey().contains("#"))
                throw ApiException.badRequest(
                    "Operation paths must be relative API paths without a host, query or fragment");
              JsonNode item = dereference(path.getValue(), doc);
              item.fields()
                  .forEachRemaining(
                      method -> {
                        if (METHODS.contains(method.getKey())) {
                          ObjectNode op = method.getValue().deepCopy();
                          // Path parameters and global security participate in both generation and
                          // change detection.
                          Map<String, JsonNode> parameters = new LinkedHashMap<>();
                          for (JsonNode p : item.path("parameters")) {
                            JsonNode resolved = dereference(p, doc);
                            parameters.put(
                                resolved.path("in").asText() + ":" + resolved.path("name").asText(),
                                p);
                          }
                          for (JsonNode p : op.path("parameters")) {
                            JsonNode resolved = dereference(p, doc);
                            parameters.put(
                                resolved.path("in").asText() + ":" + resolved.path("name").asText(),
                                p);
                          }
                          ArrayNode merged = json.createArrayNode();
                          parameters.values().forEach(merged::add);
                          op.set("parameters", merged);
                          if (!op.has("security") && doc.has("security"))
                            op.set("security", doc.get("security"));
                          // Server metadata is compared even though execution uses an explicit base
                          // URL.
                          if (!op.has("servers"))
                            op.set(
                                "servers",
                                item.has("servers") ? item.get("servers") : doc.path("servers"));
                          result.put(
                              method.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey(), op);
                        }
                      });
            });
    return result;
  }

  public JsonNode fingerprint(JsonNode operation, JsonNode root) {
    ObjectNode snapshot = json.createObjectNode();
    snapshot.set("operation", operation);
    ObjectNode dependencies = snapshot.putObject("references");
    collectReferences(operation, root, dependencies, new HashSet<>());
    for (JsonNode requirement : operation.path("security")) {
      requirement
          .fieldNames()
          .forEachRemaining(
              name -> {
                JsonNode scheme = root.path("components").path("securitySchemes").path(name);
                dependencies.set("security:" + name, scheme);
                collectReferences(scheme, root, dependencies, new HashSet<>());
              });
    }
    return snapshot;
  }

  private void collectReferences(
      JsonNode node, JsonNode root, ObjectNode dependencies, Set<String> seen) {
    if (node.has("$ref")) {
      String ref = node.path("$ref").asText();
      if (seen.add(ref)) {
        JsonNode target = root.at(ref.substring(1));
        dependencies.set(ref, target);
        collectReferences(target, root, dependencies, seen);
      }
    }
    if (node.isContainerNode())
      node.forEach(child -> collectReferences(child, root, dependencies, seen));
  }

  public JsonNode responseSchema(JsonNode schema, JsonNode root) {
    if (!schema.isObject()) return null;
    ObjectNode converted = normalizeSchema(schema);
    ObjectNode dependencies = json.createObjectNode();
    collectReferences(schema, root, dependencies, new HashSet<>());
    ObjectNode definitions = converted.putObject("definitions");
    dependencies
        .fields()
        .forEachRemaining(
            e -> definitions.set(referenceKey(e.getKey()), normalizeSchema(e.getValue())));
    rewriteSchemaReferences(converted);
    return converted;
  }

  private String referenceKey(String ref) {
    return UUID.nameUUIDFromBytes(ref.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private void rewriteSchemaReferences(JsonNode node) {
    if (node.path("$ref").isTextual()) {
      ((ObjectNode) node).put("$ref", "#/definitions/" + referenceKey(node.path("$ref").asText()));
    }
    if (node.isContainerNode()) node.forEach(this::rewriteSchemaReferences);
  }

  private ObjectNode normalizeSchema(JsonNode schema) {
    ObjectNode out = schema.deepCopy();
    for (String key : List.of("properties", "patternProperties", "definitions")) {
      if (out.path(key).isObject()) {
        ObjectNode values = (ObjectNode) out.get(key);
        List<String> names = new ArrayList<>();
        values.fieldNames().forEachRemaining(names::add);
        names.forEach(
            name -> {
              if (values.get(name).isObject()) values.set(name, normalizeSchema(values.get(name)));
            });
      }
    }
    for (String key : List.of("items", "additionalProperties", "not"))
      if (out.path(key).isObject()) out.set(key, normalizeSchema(out.get(key)));
    for (String key : List.of("allOf", "oneOf", "anyOf")) {
      if (out.path(key).isArray()) {
        ArrayNode values = (ArrayNode) out.get(key);
        for (int i = 0; i < values.size(); i++)
          if (values.get(i).isObject()) values.set(i, normalizeSchema(values.get(i)));
      }
    }
    for (String bound : List.of("Minimum", "Maximum")) {
      String exclusive = "exclusive" + bound, inclusive = bound.toLowerCase(Locale.ROOT);
      if (out.path(exclusive).isBoolean()) {
        boolean value = out.remove(exclusive).asBoolean();
        if (value && out.has(inclusive)) out.set(exclusive, out.remove(inclusive));
      }
    }
    boolean nullable = out.path("nullable").asBoolean();
    out.remove("nullable");
    out.remove(List.of("example", "xml", "externalDocs", "discriminator", "deprecated"));
    if (nullable) {
      ObjectNode wrapper = json.createObjectNode();
      wrapper.putArray("anyOf").add(out).add(json.createObjectNode().put("type", "null"));
      return wrapper;
    }
    return out;
  }
}
