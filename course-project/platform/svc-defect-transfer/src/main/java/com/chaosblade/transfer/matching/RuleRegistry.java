package com.chaosblade.transfer.matching;

import com.chaosblade.transfer.domain.Model.Template;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class RuleRegistry {
  private final JsonNode rules;

  public RuleRegistry(ObjectMapper mapper) throws IOException {
    try (var in = new ClassPathResource("operational-specs.json").getInputStream()) {
      rules = mapper.readTree(in);
    }
  }

  public JsonNode all() {
    return rules.deepCopy();
  }

  public JsonNode find(Template template) {
    JsonNode rule = rules.path(template.templateId());
    if (rule.isMissingNode()
        || rule.path("templateVersion").asInt() != template.version()
        || !rule.path("sourceHash").asText().equals(template.sourceHash())) return null;
    return rule.deepCopy();
  }
}
