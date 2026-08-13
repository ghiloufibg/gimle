package com.gimle.core.logging;

import java.util.Map;

/**
 * MDC key names shared between whatever tags a log-emitting call (currently {@code gimle-worker}'s
 * service-registration-time proxy and probe scheduler) and {@link JsonLogEncoder}/{@link
 * TextLogEncoder}, which read them back out to decide a line's category: {@code DEPLOYMENT_NAME}+
 * {@code INSTANCE_INDEX} both present means APPLICATION, absent means PLATFORM.
 */
public final class InstanceMdcKeys {

  public static final String DEPLOYMENT_NAME = "deploymentName";
  public static final String INSTANCE_INDEX = "instanceIndex";
  public static final String MODULE_ID = "moduleId";
  public static final String MODULE_VERSION = "moduleVersion";
  public static final String TENANT_ID = "tenantId";

  private InstanceMdcKeys() {}

  /**
   * The single, shared definition of the APPLICATION-vs-PLATFORM category test both encoders apply
   * to an event's MDC map -- kept in one place so the two encoders can't silently drift apart.
   */
  public static boolean isApplicationCategory(Map<String, String> mdc) {
    String deploymentName = mdc.get(DEPLOYMENT_NAME);
    return deploymentName != null && !deploymentName.isEmpty() && mdc.get(INSTANCE_INDEX) != null;
  }
}
