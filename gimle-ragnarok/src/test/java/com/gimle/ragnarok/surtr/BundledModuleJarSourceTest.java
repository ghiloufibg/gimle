package com.gimle.ragnarok.surtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.ragnarok.RagnarokException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Extraction of the bundled pause-image module jar. */
final class BundledModuleJarSourceTest {

  @Test
  void extracts_the_bundled_jar_to_a_readable_non_empty_file() throws Exception {
    final Path jar = new BundledModuleJarSource().jar("pause");
    assertTrue(Files.isRegularFile(jar), "expected a real file at " + jar);
    assertTrue(Files.size(jar) > 0, "expected a non-empty jar at " + jar);
  }

  @Test
  void a_second_call_returns_the_same_cached_path() {
    final BundledModuleJarSource source = new BundledModuleJarSource();
    final Path first = source.jar("pause");
    final Path second = source.jar("pause");
    assertEquals(first, second);
  }

  @Test
  void resolves_the_module_name() {
    assertEquals("com.gimle.ragnarok.pause", new BundledModuleJarSource().moduleName("pause"));
  }

  @Test
  void an_unknown_artifact_id_is_rejected() {
    final BundledModuleJarSource source = new BundledModuleJarSource();
    assertThrows(RagnarokException.class, () -> source.jar("not-pause"));
    assertThrows(RagnarokException.class, () -> source.moduleName("not-pause"));
  }
}
