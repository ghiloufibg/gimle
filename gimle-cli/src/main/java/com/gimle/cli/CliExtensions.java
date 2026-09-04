package com.gimle.cli;

import com.gimle.cli.spi.CliExtension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Discovery of {@link CliExtension} providers. Loaded through {@link GimleCli}'s own classloader
 * rather than the thread context one, so which providers are visible depends on how this jar was
 * loaded and not on ambient state a caller may have set.
 *
 * <p>A broken provider declaration -- a services file naming a class that isn't on the path, or one
 * that can't be instantiated -- costs the extension surface, never the CLI itself: every built-in
 * verb keeps working and the extension's verb simply isn't found, which is the unknown-verb error
 * an unextended CLI already produces.
 */
final class CliExtensions {

  private CliExtensions() {}

  static Optional<CliExtension> find(String verb) {
    for (CliExtension extension : load()) {
      if (extension.verb().equals(verb)) {
        return Optional.of(extension);
      }
    }
    return Optional.empty();
  }

  /**
   * Every discovered provider's {@link CliExtension#usageLine()}, verb-sorted for a stable help.
   */
  static List<String> usageLines() {
    List<CliExtension> extensions = new ArrayList<>(load());
    extensions.sort(Comparator.comparing(CliExtension::verb));
    return extensions.stream().map(CliExtension::usageLine).toList();
  }

  private static List<CliExtension> load() {
    try {
      return ServiceLoader.load(CliExtension.class, GimleCli.class.getClassLoader()).stream()
          .map(ServiceLoader.Provider::get)
          .toList();
    } catch (ServiceConfigurationError | RuntimeException e) {
      return List.of();
    }
  }
}
