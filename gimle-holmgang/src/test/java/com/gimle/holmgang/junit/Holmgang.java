package com.gimle.holmgang.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Boots one real cluster from the named classpath topology before any test in the class runs, and
 * tears it down after the last one. Inject the running cluster into a field annotated {@link
 * HolmgangCluster}. Work-directory retention is governed by {@code
 * -Dgimle.holmgang.keepWorkDirs=onFailure|always|never} (default {@code onFailure}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(HolmgangExtension.class)
public @interface Holmgang {

  /** Classpath resource of the topology document, e.g. {@code topologies/minimal.yaml}. */
  String topology();
}
