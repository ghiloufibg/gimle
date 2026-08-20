package com.example.sessionstore;

/**
 * The fabric service contract shared by session-store-service and session-store-client. Each
 * module bundles its own literal copy of this interface (same fully-qualified name, same
 * signature) rather than depending on a shared compile-time API jar -- the fabric's service
 * catalog resolves lookups by interface name and dispatches through a proxy built from the
 * caller's own {@code Class} object, so two independently compiled, structurally identical copies
 * interoperate correctly across the wire. The same "structural contract, not a shared jar"
 * demonstration gimle-examples/greeter-provider and greeter-consumer already establish.
 */
public interface SessionStore {

  /** Durably stores {@code value} under {@code sessionId}, replacing any previous value. */
  void put(String sessionId, String value);

  /** The value stored under {@code sessionId}, or {@code null} if none exists (a plain Java
   *  {@code Map#get} sentinel, not {@code Optional} -- {@code java.util.Optional} isn't {@code
   *  Serializable}, and this return value crosses the wire via plain Java serialization, see
   *  {@code ObjectMarshalling} in gimle-fabric). */
  String get(String sessionId);

  /** Removes {@code sessionId} if present; a no-op if it never existed or was already removed. */
  void delete(String sessionId);
}
