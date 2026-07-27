package com.gimle.core.logging;

/**
 * MDC key names shared between whatever tags a log-emitting call (currently {@code gimle-worker}'s
 * service-registration-time proxy and probe scheduler) and {@link JsonLogEncoder}, which reads them
 * back out to decide a line's {@code category} (log-explorer-design.md §3/§4): {@code
 * DEPLOYMENT_NAME}+{@code INSTANCE_INDEX} both present means APPLICATION, absent means PLATFORM.
 */
public final class InstanceMdcKeys {

  public static final String DEPLOYMENT_NAME = "deploymentName";
  public static final String INSTANCE_INDEX = "instanceIndex";
  public static final String MODULE_ID = "moduleId";
  public static final String MODULE_VERSION = "moduleVersion";
  public static final String TENANT_ID = "tenantId";

  private InstanceMdcKeys() {}
}
