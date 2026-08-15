package com.gimle.holmgang;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PUBLISH_QUIET_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * The one failsafe entry point for the Gherkin scenarios under {@code src/test/resources/features}:
 * every feature runs against a real subprocess cluster acquired by its own "Given a running cluster
 * from topology ..." step (pooled per topology; scenarios tagged {@code @destructive} get a fresh
 * cluster all to themselves -- see the steps package). Filter with standard Cucumber tag
 * expressions, e.g. {@code -Dcucumber.filter.tags="@rolling-update"}. The HTML report lands under
 * {@code target/holmgang-reports/}.
 *
 * <p>{@code pretty} is deliberately included alongside the two file-writing plugins below: without
 * it, nothing about scenario/step progress or failure ever reaches the console live, since neither
 * {@code html:...} nor {@code SagaCucumberPlugin} prints anything -- a developer watching {@code
 * -Pvalidation} run would see only Maven's own INFO lines until the whole suite finished. {@code
 * pretty} writes to this JVM's own stdout, the normal, safe way a test framework reports to its own
 * forked JVM's console (not the {@code ManagedProcess}/real-subprocess-log case where writing to
 * stdout directly would corrupt Failsafe's fork protocol -- that's a completely different stream,
 * this test JVM's own). Purely additive: it changes nothing about the HTML report or the Saga
 * collector.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.gimle.holmgang.steps")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value =
        "pretty, html:target/holmgang-reports/cucumber.html,"
            + " com.gimle.holmgang.saga.SagaCucumberPlugin")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
class HolmgangIT {}
