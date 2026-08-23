package com.gimle.fabric.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.UncheckedIOException;

/**
 * The {@code ObjectOutputStream}/{@code ObjectInputStream} byte-plumbing behind the cross-JVM
 * dynamic-proxy invocation: kept as one small, well-named helper rather than repeating the
 * stream-wrapping boilerplate at every call site in both {@code FabricServer} and the proxy's
 * {@code InvocationHandler}. No {@code ObjectInputFilter} allowlist: this only ever deserializes
 * bytes produced by another Gimlé worker inside the same trust boundary, never external input.
 */
public final class ObjectMarshalling {

  private ObjectMarshalling() {}

  public static byte[] serialize(Object value) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
      out.writeObject(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  /**
   * Resolves classes against {@code loader} rather than {@code ObjectInputStream}'s own default
   * (the caller's "latest user-defined loader" on the stack) -- a plain no-loader {@code
   * readObject()} cannot see a type private to one hosted module's own {@code ModuleLayer}, since
   * the platform-side fabric plumbing calling into this method never itself loaded that class. A
   * fabric service contract's argument, return, and exception types are exactly this shape in the
   * common case (a module bundles its own literal copy of its own domain records, the same "no
   * shared API jar" convention {@code gimle-examples/node-local-cache} demonstrates), so callers
   * must pass the classloader that's actually guaranteed to see them -- typically {@code
   * iface.getClassLoader()} for a return/exception payload, or the target service instance's own
   * {@code getClass().getClassLoader()} for an inbound argument payload.
   */
  public static Object deserialize(byte[] bytes, ClassLoader loader) {
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes)) {
          @Override
          protected Class<?> resolveClass(ObjectStreamClass desc)
              throws IOException, ClassNotFoundException {
            try {
              return Class.forName(desc.getName(), false, loader);
            } catch (ClassNotFoundException e) {
              // Falls back to the default resolution (bootstrap/platform classes, primitives,
              // arrays) -- loader is only ever guaranteed to see the fabric contract's own
              // module-private types, not every JDK type a payload might also carry.
              return super.resolveClass(desc);
            }
          }
        }) {
      return in.readObject();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("failed to deserialize a fabric invocation payload", e);
    }
  }
}
