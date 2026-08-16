package com.gimle.hilmir.release;

/** A {@link BundleSecretEntry} with every {@code ${values.*}} reference in its value resolved. */
record RenderedSecretEntry(String tenant, String key, String value) {}
