package com.gimle.ragnarok.surtr;

import com.gimle.ragnarok.RagnarokException;
import java.util.Optional;

/**
 * One templated object a {@code create} job instantiates once per iteration. Names carry the {@code
 * {{iteration}}} and {@code {{job}}} placeholders, expanded per iteration -- so one template
 * produces N distinct tenants or deployments. A deployment reuses one built reference module jar
 * under each expanded name (the "pause image" trick), scaling object count without building N jars.
 */
public record ObjectTemplate(
    Kind kind, String name, Optional<String> tenant, Optional<String> module, int replicas) {

  /** The Gimle object kinds Surtr can create in bulk. */
  public enum Kind {
    TENANT,
    DEPLOYMENT;

    static Kind fromField(final String field) {
      return switch (field) {
        case "tenant" -> TENANT;
        case "deployment" -> DEPLOYMENT;
        default -> throw new RagnarokException("unknown object kind: " + field);
      };
    }
  }

  public ObjectTemplate {
    if (kind == null) {
      throw new RagnarokException("an object template must have a kind");
    }
    if (name == null || name.isBlank()) {
      throw new RagnarokException("an object template must have a name");
    }
    if (kind == Kind.DEPLOYMENT) {
      if (module.isEmpty()) {
        throw new RagnarokException("a deployment object must name a module: " + name);
      }
      if (replicas < 1) {
        throw new RagnarokException("a deployment object must have >= 1 replicas: " + name);
      }
    }
  }

  /** This template with placeholders expanded for one iteration of one job. */
  public ObjectTemplate expand(final String jobName, final int iteration) {
    return new ObjectTemplate(
        kind,
        substitute(name, jobName, iteration),
        tenant.map(t -> substitute(t, jobName, iteration)),
        module,
        replicas);
  }

  private static String substitute(final String value, final String jobName, final int iteration) {
    return value.replace("{{iteration}}", Integer.toString(iteration)).replace("{{job}}", jobName);
  }
}
