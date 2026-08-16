package com.gimle.fabric.transport;

import java.lang.reflect.Method;
import java.util.NoSuchElementException;

/**
 * Small reflection helpers shared by {@link FabricServer}'s inbound dispatch and {@code
 * FabricServiceRegistry}'s own same-worker, name-driven invocation path -- both resolve a
 * wire-carried interface/method/parameter-type name triple against a real, already-in-hand
 * instance, with no shared platform-layer classloader able to resolve an arbitrary hosted module's
 * own service interface by name alone (gimle-api doesn't exist yet).
 */
public final class ReflectiveDispatch {

  private ReflectiveDispatch() {}

  /** {@link #resolveParamType(String, ClassLoader)}, applied element-wise. */
  public static Class<?>[] resolveParamTypes(String[] paramTypeNames, ClassLoader loader)
      throws ClassNotFoundException {
    Class<?>[] paramTypes = new Class<?>[paramTypeNames.length];
    for (int i = 0; i < paramTypes.length; i++) {
      paramTypes[i] = resolveParamType(paramTypeNames[i], loader);
    }
    return paramTypes;
  }

  /**
   * A primitive type name resolves to its own {@code Class} constant directly -- {@link
   * Class#forName(String, boolean, ClassLoader)} has no notion of an unboxed primitive by name.
   * Everything else resolves against {@code loader}.
   */
  public static Class<?> resolveParamType(String name, ClassLoader loader)
      throws ClassNotFoundException {
    return switch (name) {
      case "boolean" -> boolean.class;
      case "byte" -> byte.class;
      case "short" -> short.class;
      case "char" -> char.class;
      case "int" -> int.class;
      case "long" -> long.class;
      case "float" -> float.class;
      case "double" -> double.class;
      case "void" -> void.class;
      default -> Class.forName(name, false, loader);
    };
  }

  /**
   * The public interface {@code instanceClass} declares matching {@code interfaceName}. Reflecting
   * through this -- not {@code instanceClass} itself -- matters when the instance is a lambda or an
   * MDC-tagging proxy: both are package-private/synthetic, and {@link Method#invoke} checks the
   * *declaring class*'s accessibility, not just the method's own public modifier, so invoking
   * through the real public interface avoids an {@link IllegalAccessException}.
   */
  public static Class<?> findInterface(Class<?> instanceClass, String interfaceName) {
    for (Class<?> candidate : instanceClass.getInterfaces()) {
      if (candidate.getName().equals(interfaceName)) {
        return candidate;
      }
    }
    throw new NoSuchElementException(interfaceName + " is not implemented by " + instanceClass);
  }
}
