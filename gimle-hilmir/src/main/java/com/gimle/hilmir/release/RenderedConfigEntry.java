package com.gimle.hilmir.release;

/** A {@link BundleConfigEntry} with every {@code ${values.*}} reference in its value resolved. */
record RenderedConfigEntry(String tenant, String key, String value) {}
