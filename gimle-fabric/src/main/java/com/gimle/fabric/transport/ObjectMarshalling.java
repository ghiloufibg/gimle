package com.gimle.fabric.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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

  public static Object deserialize(byte[] bytes) {
    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
      return in.readObject();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("failed to deserialize a fabric invocation payload", e);
    }
  }
}
