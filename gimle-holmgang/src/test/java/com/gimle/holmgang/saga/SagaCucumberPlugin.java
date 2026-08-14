package com.gimle.holmgang.saga;

import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Status;
import io.cucumber.plugin.event.TestCase;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestSourceRead;
import io.cucumber.plugin.event.TestStepFinished;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Cucumber event listener that translates each scenario into the Saga report's scenario shape and
 * feeds it to the shared {@link SagaCollector}. Registered as a plugin on the Holmgang suite; the
 * collector's own shutdown hook does the writing, so this only accumulates.
 *
 * <p>The topology a scenario ran on is recovered best-effort from its step text (the {@code Given a
 * running "<name>" cluster ...} steps), since Cucumber's event model doesn't carry it; a forensic
 * report path, when a failure names one, is lifted out of the error message.
 */
public final class SagaCucumberPlugin implements ConcurrentEventListener {

  private static final Pattern FEATURE_LINE = Pattern.compile("(?m)^\\s*Feature:\\s*(.+)$");
  private static final Pattern TOPOLOGY_IN_TOPOLOGY = Pattern.compile("topology \"([^\"]+)\"");
  private static final Pattern TOPOLOGY_IN_CLUSTER = Pattern.compile("\"([^\"]+)\" cluster");

  private final Map<URI, String> featureNames = new ConcurrentHashMap<>();
  private final Map<TestCase, List<Map<String, Object>>> steps = new ConcurrentHashMap<>();
  private final Map<TestCase, String> topologies = new ConcurrentHashMap<>();

  @Override
  public void setEventPublisher(final EventPublisher publisher) {
    publisher.registerHandlerFor(TestSourceRead.class, this::onSource);
    publisher.registerHandlerFor(TestStepFinished.class, this::onStepFinished);
    publisher.registerHandlerFor(TestCaseFinished.class, this::onCaseFinished);
  }

  private void onSource(final TestSourceRead event) {
    final Matcher m = FEATURE_LINE.matcher(event.getSource());
    if (m.find()) {
      featureNames.put(event.getUri(), m.group(1).trim());
    }
  }

  private void onStepFinished(final TestStepFinished event) {
    if (!(event.getTestStep() instanceof PickleStepTestStep pickle)) {
      return;
    }
    final TestCase testCase = event.getTestCase();
    final String text = pickle.getStep().getText();
    final Map<String, Object> step = new LinkedHashMap<>();
    step.put("keyword", pickle.getStep().getKeyword().trim());
    step.put("text", text);
    step.put("status", mapStatus(event.getResult().getStatus()));
    step.put("durationMillis", event.getResult().getDuration().toMillis());
    steps.computeIfAbsent(testCase, k -> new ArrayList<>()).add(step);
    topologies.computeIfAbsent(testCase, k -> extractTopology(text));
  }

  private void onCaseFinished(final TestCaseFinished event) {
    final TestCase testCase = event.getTestCase();
    final Map<String, Object> scenario = new LinkedHashMap<>();
    scenario.put("name", testCase.getName());
    scenario.put("status", mapStatus(event.getResult().getStatus()));
    scenario.put("durationMillis", event.getResult().getDuration().toMillis());
    scenario.put("topology", topologies.getOrDefault(testCase, ""));
    scenario.put("tags", new ArrayList<>(testCase.getTags()));
    scenario.put("steps", steps.getOrDefault(testCase, new ArrayList<>()));
    scenario.put("failure", SagaCollector.failureFrom(event.getResult().getError()));

    final URI uri = testCase.getUri();
    final String file = fileName(uri);
    final String featureName = featureNames.getOrDefault(uri, file.replace(".feature", ""));
    SagaCollector.instance().addScenario(featureName, file, scenario);

    steps.remove(testCase);
    topologies.remove(testCase);
  }

  private static String extractTopology(final String stepText) {
    final Matcher a = TOPOLOGY_IN_TOPOLOGY.matcher(stepText);
    if (a.find()) {
      return a.group(1);
    }
    final Matcher b = TOPOLOGY_IN_CLUSTER.matcher(stepText);
    if (b.find()) {
      return b.group(1);
    }
    return "";
  }

  private static String fileName(final URI uri) {
    final String path = uri.getSchemeSpecificPart();
    final int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private static String mapStatus(final Status status) {
    return switch (status) {
      case PASSED -> "PASSED";
      case FAILED -> "FAILED";
      default -> "SKIPPED";
    };
  }
}
