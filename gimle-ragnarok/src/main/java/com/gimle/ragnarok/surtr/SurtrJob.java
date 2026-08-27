package com.gimle.ragnarok.surtr;

import com.gimle.ragnarok.RagnarokException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * One job in a workload: a {@code create} storm of templated objects at a controlled rate, a {@code
 * churn} that redeploys or recreates a fraction of a prior job's objects in cycles, or a {@code
 * delete} that removes them and measures convergence-to-absent.
 */
public record SurtrJob(
    String name,
    Type type,
    int iterations,
    double qps,
    int burst,
    List<ObjectTemplate> objects,
    Duration waitForActive,
    ChurnSpec churn,
    Optional<String> target) {

  public enum Type {
    CREATE,
    CHURN,
    DELETE;

    static Type fromField(final String field) {
      return switch (field) {
        case "create" -> CREATE;
        case "churn" -> CHURN;
        case "delete" -> DELETE;
        default -> throw new RagnarokException("unknown job type: " + field);
      };
    }
  }

  /** How a churn job perturbs its target population each cycle. */
  public enum ChurnMode {
    /** Submit each chosen deployment at a bumped version -- a rolling redeploy. */
    REDEPLOY,
    /** Delete then recreate each chosen deployment. */
    RECREATE;

    static ChurnMode fromField(final String field) {
      return switch (field) {
        case "redeploy" -> REDEPLOY;
        case "recreate" -> RECREATE;
        default -> throw new RagnarokException("unknown churn mode: " + field);
      };
    }
  }

  /** The churn knobs; only meaningful for a {@link Type#CHURN} job. */
  public record ChurnSpec(Duration duration, int percent, ChurnMode mode) {}

  public SurtrJob {
    if (name == null || name.isBlank()) {
      throw new RagnarokException("a job must have a name");
    }
    if (type == null) {
      throw new RagnarokException("a job must have a type: " + name);
    }
    objects = List.copyOf(objects);
    switch (type) {
      case CREATE -> {
        if (iterations < 1) {
          throw new RagnarokException("a create job must have >= 1 iterations: " + name);
        }
        if (objects.isEmpty()) {
          throw new RagnarokException("a create job must declare at least one object: " + name);
        }
        if (qps <= 0) {
          throw new RagnarokException("a create job must have a positive qps: " + name);
        }
        if (burst < 1) {
          throw new RagnarokException("a create job must have burst >= 1: " + name);
        }
      }
      case CHURN -> {
        if (churn == null) {
          throw new RagnarokException("a churn job must declare a churn section: " + name);
        }
        if (churn.percent() < 1 || churn.percent() > 100) {
          throw new RagnarokException("churn.percent must be 1..100: " + name);
        }
        if (target.isEmpty()) {
          throw new RagnarokException("a churn job must name a target create job: " + name);
        }
      }
      case DELETE -> {
        if (target.isEmpty()) {
          throw new RagnarokException("a delete job must name a target create job: " + name);
        }
      }
    }
  }
}
