package com.gimle.holmgang.junit;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.WorkDirs;
import com.gimle.holmgang.cluster.GimleCluster;
import com.gimle.holmgang.topology.ClusterSpec;
import com.gimle.holmgang.topology.ClusterTopologyParser;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * The lifecycle behind {@link Holmgang}: one cluster per test class, booted before the first test
 * and closed after the last, injected into {@link HolmgangCluster}-annotated fields before each
 * test. The cluster's work directory lands under {@code target/holmgang/} and survives according to
 * {@code gimle.holmgang.keepWorkDirs}: {@code onFailure} (default) keeps it only when a test in the
 * class failed -- the process logs are the forensic artifact -- {@code always} keeps every run's,
 * {@code never} keeps none.
 */
final class HolmgangExtension
    implements BeforeAllCallback, BeforeEachCallback, AfterAllCallback, TestWatcher {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(HolmgangExtension.class);

  private static final class ClusterHolder {
    private GimleCluster cluster;
    private Path workDir;
    private boolean anyTestFailed;
  }

  @Override
  public void beforeAll(final ExtensionContext context) {
    final Class<?> testClass = context.getRequiredTestClass();
    final Holmgang annotation = testClass.getAnnotation(Holmgang.class);
    if (annotation == null) {
      throw new HolmgangException(
          testClass.getName() + " uses HolmgangExtension without an @Holmgang annotation");
    }
    final ClusterSpec spec = ClusterTopologyParser.fromClasspath(annotation.topology());
    final Path workDir =
        Path.of("target", "holmgang", spec.name() + "-" + Long.toHexString(System.nanoTime()));
    final ClusterHolder holder = new ClusterHolder();
    holder.workDir = workDir;
    holder.cluster = GimleCluster.start(spec, workDir);
    context.getStore(NAMESPACE).put(testClass, holder);
  }

  @Override
  public void beforeEach(final ExtensionContext context) {
    final ClusterHolder holder = holder(context);
    final Object instance = context.getRequiredTestInstance();
    for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
      for (final Field field : type.getDeclaredFields()) {
        if (field.isAnnotationPresent(HolmgangCluster.class)) {
          if (!GimleCluster.class.isAssignableFrom(field.getType())) {
            throw new HolmgangException(
                "@HolmgangCluster field "
                    + field.getName()
                    + " must be of type GimleCluster, is "
                    + field.getType().getName());
          }
          field.setAccessible(true);
          try {
            field.set(instance, holder.cluster);
          } catch (final IllegalAccessException e) {
            throw new HolmgangException("failed injecting cluster into " + field.getName(), e);
          }
        }
      }
    }
  }

  @Override
  public void testFailed(final ExtensionContext context, final Throwable cause) {
    holder(context).anyTestFailed = true;
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    final ClusterHolder holder = holder(context);
    if (holder.cluster != null) {
      holder.cluster.close();
    }
    if (WorkDirs.shouldDelete(holder.anyTestFailed)) {
      WorkDirs.deleteRecursively(holder.workDir);
    }
  }

  private static ClusterHolder holder(final ExtensionContext context) {
    final ClusterHolder holder =
        context.getStore(NAMESPACE).get(context.getRequiredTestClass(), ClusterHolder.class);
    if (holder == null) {
      throw new HolmgangException(
          "no cluster was booted for " + context.getRequiredTestClass().getName());
    }
    return holder;
  }
}
