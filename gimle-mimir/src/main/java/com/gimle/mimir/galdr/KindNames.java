package com.gimle.mimir.galdr;

import java.util.List;
import java.util.Optional;

/**
 * The CLI/console nicknames a {@link KindDefinitionSpec} declares for itself -- a plural noun
 * ({@code greetings}) and zero or more short names ({@code gr}), resolved by the CLI's noun
 * dispatch after the exact prefixed kind name fails to match. Both live in one flat cluster-wide
 * namespace shared with every other definition's declared names, checked for collision when the
 * definition is admitted.
 */
public record KindNames(Optional<String> plural, List<String> shortNames) {

  public KindNames {
    shortNames = List.copyOf(shortNames);
  }

  public static KindNames none() {
    return new KindNames(Optional.empty(), List.of());
  }
}
