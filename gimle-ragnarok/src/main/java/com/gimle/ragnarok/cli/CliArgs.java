package com.gimle.ragnarok.cli;

import com.gimle.ragnarok.RagnarokException;
import java.util.List;
import java.util.Optional;

/**
 * The small {@code --flag value} scanning helpers every verb command hand-parses its own arguments
 * with -- mirroring, not reusing, {@code gimle-cli}'s own {@code Flags} class (this module has no
 * reason to depend on {@code gimle-cli}, a control-plane-API-client leaf module with the opposite
 * dependency direction); the shape matches {@code gimle-hilmir}'s own small per-verb helpers
 * instead, the closer precedent for a new standalone tool.
 */
final class CliArgs {

  private CliArgs() {}

  static Optional<String> optionalFlag(final List<String> args, final String flag) {
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).equals(flag) && i + 1 < args.size()) {
        return Optional.of(args.get(i + 1));
      }
    }
    return Optional.empty();
  }

  static String requireFlag(final List<String> args, final String flag) {
    return optionalFlag(args, flag)
        .orElseThrow(() -> new RagnarokException("missing required flag: " + flag));
  }

  static boolean flagPresent(final List<String> args, final String flag) {
    return args.contains(flag);
  }

  static Optional<Long> optionalLongFlag(final List<String> args, final String flag) {
    return optionalFlag(args, flag)
        .map(
            value -> {
              try {
                return Long.parseLong(value);
              } catch (final NumberFormatException e) {
                throw new RagnarokException(flag + " must be an integer, got: " + value);
              }
            });
  }
}
