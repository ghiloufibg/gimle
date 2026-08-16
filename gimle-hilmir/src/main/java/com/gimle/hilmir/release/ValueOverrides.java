package com.gimle.hilmir.release;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Merges a bundle's own {@code values:} defaults with an optional {@code --values <file>} override
 * and any number of repeatable {@code --set key=value} flags, in that precedence order -- Helm's
 * own (chart defaults, then a values file, then individual {@code --set} flags win last) -- into
 * the map {@link BundleRenderer} then resolves every {@code ${values.*}} reference against.
 */
final class ValueOverrides {

  private ValueOverrides() {}

  static Map<String, String> merge(
      Map<String, String> bundleDefaults, Optional<Path> valuesFile, List<String> setFlags) {
    Map<String, String> merged = new LinkedHashMap<>(bundleDefaults);
    valuesFile.ifPresent(file -> merged.putAll(readValuesFile(file)));
    for (String setFlag : setFlags) {
      int eq = setFlag.indexOf('=');
      if (eq < 0) {
        throw new HilmirException("--set must be in key=value form, got: " + setFlag);
      }
      merged.put(setFlag.substring(0, eq), setFlag.substring(eq + 1));
    }
    return merged;
  }

  /**
   * A flat YAML mapping of key to scalar value -- the same "properties-shaped" flat structure the
   * bundle's own {@code values:} block uses, just as a standalone file rather than embedded in the
   * bundle document.
   */
  private static Map<String, String> readValuesFile(Path file) {
    Object raw;
    try (InputStream in = Files.newInputStream(file)) {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      raw = yaml.load(in);
    } catch (IOException e) {
      throw new HilmirException("could not read values file " + file + ": " + e.getMessage(), e);
    } catch (RuntimeException e) {
      throw new GimleManifestException("malformed YAML in values file " + file, e);
    }
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new GimleManifestException("values file " + file + " must contain a flat YAML mapping");
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new GimleManifestException("values file " + file + " keys must be strings");
      }
      Object scalar = entry.getValue();
      if (scalar instanceof Map || scalar instanceof List) {
        throw new GimleManifestException(
            "values file " + file + " key '" + key + "' must be a scalar, not nested");
      }
      values.put(key, scalar == null ? "" : String.valueOf(scalar));
    }
    return values;
  }
}
