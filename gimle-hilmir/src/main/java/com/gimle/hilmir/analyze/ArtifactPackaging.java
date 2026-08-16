package com.gimle.hilmir.analyze;

/**
 * What kind of on-disk artifact {@code doctor}/{@code init} were pointed at, decided from the
 * file's own extension/shape before any jar entry is ever read -- only {@link #PLAIN_JAR} is
 * something the rest of the analyzer can open and inspect further.
 */
public enum ArtifactPackaging {
  /** An ordinary jar (by extension and ZIP magic number) -- the only shape doctor/init can host. */
  PLAIN_JAR,
  /** A {@code .war} -- a servlet container archive, not a Gimlé artifact of any hosting mode. */
  WAR,
  /** A {@code .ear} -- a Java EE enterprise archive, not a Gimlé artifact of any hosting mode. */
  EAR,
  /** A directory-shaped distribution (e.g. an exploded/fast-jar layout) rather than one file. */
  DIRECTORY,
  /**
   * Not a ZIP-shaped file at all -- most commonly a native-image executable or another non-JVM
   * binary; indistinguishable from the file's bytes alone which specific native format it is, so
   * this is reported generically.
   */
  NOT_A_JAR
}
