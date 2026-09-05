package com.gimle.hilmir.release;

/**
 * A {@link BundleSecretEntry} with every {@code ${values.*}} reference in its value resolved.
 *
 * <p>Public because {@link RenderedBundle} already exposes a list of these, so a caller outside
 * this package that reads or filters a rendered bundle's secrets -- Ivaldi dropping the ones whose
 * value nobody supplied, say -- cannot otherwise name the type it is handed.
 */
public record RenderedSecretEntry(String tenant, String key, String value) {}
