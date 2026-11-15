package com.chaosblade.transfer;

import static org.junit.jupiter.api.Assertions.*;

import com.chaosblade.transfer.domain.Model.Template;
import com.chaosblade.transfer.store.SnapshotStore;
import com.chaosblade.transfer.template.TemplateService;
import com.chaosblade.transfer.web.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class TemplateStoreTest {
  @TempDir Path dir;
  SnapshotStore store;
  TemplateService templates;
  String markdown;

  @BeforeEach
  void setup() throws Exception {
    store = new SnapshotStore(dir.toString(), new ObjectMapper());
    templates = new TemplateService(store);
    markdown =
        new ClassPathResource("chaosblade_defect_templates.md")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void importsAllPreservingDraftAndProvenanceAndIdempotency() {
    var first = templates.importMarkdown(markdown);
    assertEquals(15, first.size());
    assertTrue(first.stream().allMatch(x -> x.created() && x.status().equals("draft")));
    var t = templates.get("DT-DUALWRITE-LOST-001", 1);
    assertTrue(t.document().path("provenance").path("instances").isEmpty());
    assertTrue(templates.importMarkdown(markdown).stream().noneMatch(x -> x.created()));
    assertEquals(15, store.list("templates").size());
  }

  @Test
  void conflictsAreRejectedBeforeWritingAnyOtherVersion() {
    templates.importMarkdown(markdown);
    String changed =
        markdown
            .replace("template_id: DT-RETRY-DUP-001", "template_id: DT-NEW-DUP-001")
            .replace("发布失败只记日志", "发布失败必须记录日志");
    Problem p = assertThrows(Problem.class, () -> templates.importMarkdown(changed));
    assertEquals(409, p.status());
    assertFalse(store.exists("templates", "DT-NEW-DUP-001-v1"));
  }

  @Test
  void badYamlAndDuplicateKeysCannotLeavePartialBatch() {
    assertThrows(
        Problem.class,
        () ->
            templates.importMarkdown(
                markdown + "\n```yaml\ntemplate_id: A\ntemplate_id: B\n```\n"));
    assertTrue(store.list("templates").isEmpty());
    assertThrows(Problem.class, () -> templates.importMarkdown("no yaml"));
  }

  @Test
  void duplicateVersionsWithinBatchAreRejected() {
    assertThrows(Problem.class, () -> templates.importMarkdown(markdown + "\n" + markdown));
    assertTrue(store.list("templates").isEmpty());
  }

  @Test
  void snapshotSurvivesReopeningAndRejectsOverwriteAndPathEscape() throws Exception {
    templates.importMarkdown(markdown);
    var reopened = new SnapshotStore(dir.toString(), new ObjectMapper());
    Template original = reopened.get("templates", "DT-DUALWRITE-LOST-001-v1", Template.class);
    assertEquals("draft", original.status());
    assertEquals(
        409,
        assertThrows(
                Problem.class,
                () ->
                    reopened.put(
                        "templates",
                        "DT-DUALWRITE-LOST-001-v1",
                        new ObjectMapper().createObjectNode()))
            .status());
    assertThrows(Problem.class, () -> reopened.read("profiles", "../../secret"));
  }
}
