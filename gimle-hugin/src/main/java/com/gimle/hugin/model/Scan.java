package com.gimle.hugin.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads every snapshot the view already holds and reports what is wrong with the cluster, so that
 * the answer to "is anything broken" is one screen rather than a walk through six tables.
 *
 * <p>Every check here is arithmetic over readings drawn elsewhere -- no request of its own is made,
 * and nothing is reported that some other screen could not also be made to show. What it adds is
 * that they are all in one place and ordered by how much they matter, which is the whole of it: an
 * operator looking for trouble should not have to already know which table it would appear in.
 *
 * <p>A check whose input is missing is never silently skipped. A scan that came back clean because
 * a read failed is worse than no scan, so the gap is reported as a finding of its own.
 */
public final class Scan {

  /** Where a node's capacity stops being headroom and starts being a problem. */
  private static final double CROWDED = 0.9;

  private Scan() {}

  public static List<ScanFinding> findings(
      final ClusterSnapshot cluster,
      final ServiceSnapshot services,
      final Instant now,
      final String filter) {
    List<ScanFinding> findings = new ArrayList<>();
    nodeFindings(cluster, now, findings);
    workloadFindings(cluster, findings);
    instanceFindings(cluster, findings);
    serviceFindings(cluster, services, findings);

    List<ScanFinding> ordered =
        findings.stream()
            .sorted(
                Comparator.comparing(ScanFinding::severity)
                    .thenComparing(ScanFinding::group)
                    .thenComparing(ScanFinding::subject))
            .toList();
    if (filter == null || filter.isBlank()) {
      return ordered;
    }
    String needle = filter.toLowerCase(Locale.ROOT);
    return ordered.stream().filter(finding -> finding.searchText().contains(needle)).toList();
  }

  private static void nodeFindings(
      final ClusterSnapshot cluster, final Instant now, final List<ScanFinding> findings) {
    for (NodeRow node : cluster.nodes()) {
      if (node.isStale(now)) {
        // The agent has stopped reporting, so every instance this node claims to run is a claim
        // nothing has confirmed since. That makes it the first thing to look at, not a warning.
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.ERROR,
                "nodes",
                node.nodeId(),
                node.heartbeatAge(now)
                    .map(age -> "no heartbeat for " + age.toSeconds() + "s")
                    .orElse("has never heartbeated")));
      }
      if (node.cordoned()) {
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.NOTE,
                "nodes",
                node.nodeId(),
                "cordoned, so the scheduler will place nothing new here"));
      }
      if (node.hasCapacity()) {
        crowding(node, findings);
      }
    }
  }

  /**
   * A node close to full is reported before placement actually fails, because by the time it fails
   * the finding is a workload's unplaced replicas and no longer says which machine ran out.
   */
  private static void crowding(final NodeRow node, final List<ScanFinding> findings) {
    double cpu = fraction(node.assignedCpuMillicores(), node.totalCpuMillicores());
    double memory = fraction(node.assignedMemoryBytes(), node.totalMemoryBytes());
    if (cpu >= CROWDED || memory >= CROWDED) {
      findings.add(
          new ScanFinding(
              ScanFinding.Severity.WARNING,
              "nodes",
              node.nodeId(),
              "committed to "
                  + percent(cpu)
                  + " of its cpu and "
                  + percent(memory)
                  + " of its memory"));
    }
  }

  private static void workloadFindings(
      final ClusterSnapshot cluster, final List<ScanFinding> findings) {
    for (WorkloadRow workload : cluster.workloads()) {
      String subject = subject(workload.tenantId(), workload.name());
      if (workload.unplacedCount() > 0) {
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.ERROR,
                "workloads",
                subject,
                workload.placedCount()
                    + " of "
                    + workload.desiredReplicas()
                    + " replicas placed, "
                    + workload.unplacedCount()
                    + " with nowhere to go"));
      }
      if (workload.quotaViolating()) {
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.WARNING, "workloads", subject, "over its tenant's quota"));
      }
      if (workload.limitRangeViolating()) {
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.WARNING,
                "workloads",
                subject,
                "outside its tenant's limit range: "
                    + workload.limitRangeViolationReason().orElse("workload rejected")));
      }
      if (workload.desiredReplicas() == 0) {
        // Deliberate, and indistinguishable at a glance from a workload whose replicas all died --
        // which is exactly why it is worth one line rather than being left to be guessed at.
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.NOTE,
                "workloads",
                subject,
                "scaled to zero, so it is running nothing on purpose"));
      }
    }
  }

  private static void instanceFindings(
      final ClusterSnapshot cluster, final List<ScanFinding> findings) {
    for (InstanceRow instance : cluster.instances()) {
      String subject =
          subject(instance.tenantId(), instance.deploymentName() + "/" + instance.instanceIndex());
      if (!instance.observed()) {
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.WARNING,
                "instances",
                subject,
                "placed on " + instance.nodeId() + ", which has reported nothing about it yet"));
        continue;
      }
      if ("FAILED".equals(instance.lifecycleState())) {
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.ERROR,
                "instances",
                subject,
                "FAILED on " + instance.nodeId()));
        continue;
      }
      probeFindings(instance, subject, findings);
    }
  }

  /**
   * Probe verdicts are only read for an instance that has finished starting. One still coming up is
   * not ready yet by design, and reporting that would fill the screen with findings that resolve
   * themselves.
   */
  private static void probeFindings(
      final InstanceRow instance, final String subject, final List<ScanFinding> findings) {
    if (!"ACTIVE".equals(instance.lifecycleState())) {
      return;
    }
    if (!instance.alive()) {
      findings.add(
          new ScanFinding(
              ScanFinding.Severity.ERROR,
              "instances",
              subject,
              "failing its liveness probe on " + instance.nodeId()));
    } else if (!instance.ready()) {
      findings.add(
          new ScanFinding(
              ScanFinding.Severity.WARNING,
              "instances",
              subject,
              "active but not ready, so nothing is being routed to it"));
    }
  }

  /**
   * The two Service findings, plus the one that says the Service checks did not run at all.
   *
   * <p>A Service naming a workload the cluster does not have and one whose backing instances have
   * all gone are told apart, because they are different mistakes and are fixed in different places.
   */
  private static void serviceFindings(
      final ClusterSnapshot cluster,
      final ServiceSnapshot services,
      final List<ScanFinding> findings) {
    if (services.fetchedAt().isEmpty()) {
      findings.add(
          new ScanFinding(
              ScanFinding.Severity.NOTE,
              "services",
              "not read",
              services.staleReason().orElse("no read has completed")
                  + ", so nothing about Services was checked"));
      return;
    }
    Set<String> declared = new LinkedHashSet<>();
    for (WorkloadRow workload : cluster.workloads()) {
      declared.add(key(workload.tenantId(), workload.name()));
    }
    for (ServiceRow service : services.services()) {
      String subject = subject(service.tenantId(), service.name());
      if (service.unresolved()) {
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.ERROR,
                "services",
                subject,
                "resolves to no endpoint, so a call to it lands nowhere"));
      } else if (service.endpointCount().isEmpty()) {
        findings.add(
            new ScanFinding(
                ScanFinding.Severity.WARNING,
                "services",
                subject,
                "its endpoints could not be read, so whether it resolves is unknown"));
      }
      for (String deployment : service.deploymentNames()) {
        if (!declared.contains(key(service.tenantId(), deployment))) {
          findings.add(
              new ScanFinding(
                  ScanFinding.Severity.ERROR,
                  "services",
                  subject,
                  "fronts '" + deployment + "', which is not a workload this cluster has"));
        }
      }
    }
  }

  /**
   * A Service declares no tenant when it fronts the untenanted namespace and so does a workload
   * there, so absent matches absent and a tenanted Service only ever reaches its own tenant's
   * workloads.
   */
  private static String key(final Optional<String> tenantId, final String name) {
    return tenantId.orElse("") + "/" + name;
  }

  /** The tenant is part of the name here: two tenants may each have a {@code checkout-api}. */
  private static String subject(final Optional<String> tenantId, final String name) {
    return tenantId.map(tenant -> tenant + "/" + name).orElse(name);
  }

  private static double fraction(final long used, final long total) {
    return total <= 0 ? 0.0 : (double) used / total;
  }

  private static String percent(final double fraction) {
    return Math.round(fraction * 100) + "%";
  }
}
