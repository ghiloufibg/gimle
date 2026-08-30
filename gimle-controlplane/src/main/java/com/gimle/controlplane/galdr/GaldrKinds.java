package com.gimle.controlplane.galdr;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.mimir.galdr.KindDefinitionSpec;
import java.util.List;
import java.util.Optional;

/**
 * The small, store-independent decisions custom-kind admission shares between routes: resolving a
 * kind name against the current definition catalog with the catalog-in-the-error contract, and the
 * flat declared-names (plural/shortNames) collision check run when a definition is admitted.
 */
public final class GaldrKinds {

  private GaldrKinds() {}

  /**
   * The definition for {@code kindName}, or a {@link GimleManifestException} whose message carries
   * the current catalog -- so a typo'd kind fails with the actual choices in hand, not a bare "not
   * found".
   */
  public static KindDefinitionSpec requireDefinition(
      String kindName, List<KindDefinitionSpec> definitions) {
    return definitions.stream()
        .filter(definition -> definition.kindName().equals(kindName))
        .findFirst()
        .orElseThrow(() -> new GimleManifestException(unknownKindMessage(kindName, definitions)));
  }

  public static String unknownKindMessage(String kindName, List<KindDefinitionSpec> definitions) {
    List<String> known = definitions.stream().map(KindDefinitionSpec::kindName).sorted().toList();
    if (known.isEmpty()) {
      return "unknown kind '"
          + kindName
          + "' -- no KindDefinition with that name; no kinds are"
          + " defined yet";
    }
    return "unknown kind '"
        + kindName
        + "' -- no KindDefinition with that name; defined kinds: "
        + String.join(", ", known);
  }

  /**
   * Plural and short names live in one flat cluster-wide namespace shared with each other across
   * every definition -- checked here when a definition is admitted, excluding the definition's own
   * kind so a re-PUT never collides with itself. Returns the colliding name, if any.
   */
  public static Optional<String> declaredNameCollision(
      KindDefinitionSpec submitted, List<KindDefinitionSpec> existing) {
    for (KindDefinitionSpec other : existing) {
      if (other.kindName().equals(submitted.kindName())) {
        continue;
      }
      for (String declared : declaredNames(submitted)) {
        if (declaredNames(other).contains(declared)) {
          return Optional.of(declared + " (already declared by " + other.kindName() + ")");
        }
      }
    }
    // The submitted definition's own plural/shortNames must not collide with each other either.
    List<String> own = declaredNames(submitted);
    for (int i = 0; i < own.size(); i++) {
      if (own.subList(0, i).contains(own.get(i))) {
        return Optional.of(own.get(i) + " (declared twice by this definition)");
      }
    }
    return Optional.empty();
  }

  private static List<String> declaredNames(KindDefinitionSpec definition) {
    List<String> names = new java.util.ArrayList<>();
    definition.names().plural().ifPresent(names::add);
    names.addAll(definition.names().shortNames());
    return names;
  }
}
