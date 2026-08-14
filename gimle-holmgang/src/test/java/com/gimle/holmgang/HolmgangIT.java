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
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.gimle.holmgang.steps")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "html:target/holmgang-reports/cucumber.html")
@ConfigurationParameter(key = PLUGIN_PUBLISH_QUIET_PROPERTY_NAME, value = "true")
class HolmgangIT {}
