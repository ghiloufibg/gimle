package com.gimle.hilmir.analyze;

/**
 * The fully-qualified binary names of the four interfaces a hosted module's own bundled classes may
 * implement, matched textually against a scanned class's declared {@code implements} clause --
 * {@code gimle-hilmir} cannot depend on {@code gimle-module} (the module that actually declares
 * these interfaces), so a name-only string comparison is the only way to recognize an implementer
 * without loading the class.
 */
public final class ProbeInterfaces {

  public static final String LIVENESS_PROBE = "com.gimle.module.probe.LivenessProbe";
  public static final String READINESS_PROBE = "com.gimle.module.probe.ReadinessProbe";
  public static final String MODULE_LIFECYCLE_HOOKS =
      "com.gimle.module.lifecycle.ModuleLifecycleHooks";
  public static final String JOB_HOOKS = "com.gimle.module.lifecycle.JobHooks";

  private ProbeInterfaces() {}
}
