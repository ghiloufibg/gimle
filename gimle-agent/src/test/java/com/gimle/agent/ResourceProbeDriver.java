package com.gimle.agent;

/**
 * Prints this JVM's actual processor/memory ceiling to stdout, so {@link
 * ResourceLimitEnforcementTest} can verify a real spawned subprocess honors the {@code -Xmx}/{@code
 * -XX:ActiveProcessorCount} flags {@code PortableJvmFlagsResourceLimiter} computed for it, not just
 * that those flags were computed and appended to a command list.
 */
public final class ResourceProbeDriver {

  private ResourceProbeDriver() {}

  public static void main(String[] args) {
    System.out.println("AVAILABLE_PROCESSORS=" + Runtime.getRuntime().availableProcessors());
    System.out.println("MAX_MEMORY=" + Runtime.getRuntime().maxMemory());
  }
}
