package dev.apiguard;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

class SpecEngineTest {
  final ObjectMapper json = new ObjectMapper();
  final SpecEngine engine = new SpecEngine(json, mock(PlatformStore.class));

  @Test
  void translatesNullableAndExclusiveBounds() throws Exception {
    var schema =
        json.readTree(
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"nullable\":true},\"price\":{\"type\":\"number\",\"minimum\":0,\"exclusiveMinimum\":true}}}");
    var converted = engine.responseSchema(schema, json.createObjectNode());
    var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(converted);
    assertThat(validator.validate(json.readTree("{\"name\":null,\"price\":1}"))).isEmpty();
    assertThat(validator.validate(json.readTree("{\"name\":4,\"price\":0}")))
        .anyMatch(e -> e.getInstanceLocation().toString().equals("$.name"))
        .anyMatch(e -> e.getType().equals("exclusiveMinimum"));
  }

  @Test
  void validatesRecursiveLocalSchemaReferencesWithoutExpandingForever() throws Exception {
    var root =
        json.readTree(
            "{\"components\":{\"schemas\":{\"Node\":{\"type\":\"object\",\"required\":[\"value\"],\"properties\":{\"value\":{\"type\":\"integer\"},\"child\":{\"$ref\":\"#/components/schemas/Node\"}}}}}}");
    var schema =
        engine.responseSchema(json.readTree("{\"$ref\":\"#/components/schemas/Node\"}"), root);
    var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema);
    assertThat(validator.validate(json.readTree("{\"value\":1,\"child\":{\"value\":2}}")))
        .isEmpty();
    assertThat(validator.validate(json.readTree("{\"value\":1,\"child\":{\"value\":\"bad\"}}")))
        .isNotEmpty();
  }

  @Test
  void nestedReferencesAreIncludedInImpactFingerprint() throws Exception {
    var root =
        json.readTree(
            "{\"components\":{\"schemas\":{\"A\":{\"properties\":{\"b\":{\"$ref\":\"#/components/schemas/B\"}}},\"B\":{\"type\":\"string\"}}}}");
    var fingerprint =
        engine.fingerprint(json.readTree("{\"$ref\":\"#/components/schemas/A\"}"), root);
    assertThat(fingerprint.path("references").size()).isEqualTo(2);
  }

  @Test
  void operationParametersOverridePathParameters() throws Exception {
    var doc =
        json.readTree(
            "{\"paths\":{\"/items/{id}\":{\"parameters\":[{\"in\":\"path\",\"name\":\"id\",\"example\":1}],\"get\":{\"parameters\":[{\"in\":\"path\",\"name\":\"id\",\"example\":2}]}}}}");
    var parameters = engine.operations(doc).get("GET /items/{id}").path("parameters");
    assertThat(parameters.size()).isEqualTo(1);
    assertThat(parameters.get(0).path("example").asInt()).isEqualTo(2);
  }
}
