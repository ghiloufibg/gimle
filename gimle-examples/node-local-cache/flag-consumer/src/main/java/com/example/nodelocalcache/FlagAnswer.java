package com.example.nodelocalcache;

import java.io.Serializable;

/**
 * A {@link FeatureFlagCache#get} result. {@code implements Serializable} is required here, not
 * optional: this crosses the wire via plain Java serialization (see {@code ObjectMarshalling} in
 * gimle-fabric), and a record gets no serialization support unless it says so itself -- unlike
 * {@code java.util.Optional}, which never gets it at all, {@link String}/primitive components
 * alone don't make the record itself serializable.
 */
public record FlagAnswer(boolean value, String replicaId) implements Serializable {}
