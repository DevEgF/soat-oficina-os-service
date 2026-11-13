package com.soat.tech.challenge.oficina.os.bdd

import io.cucumber.junit.platform.engine.Constants.ANSI_COLORS_DISABLED_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite

/**
 * Runs the Gherkin scenarios of src/test/resources/features with the Cucumber engine, inside the regular
 * `test` task (and so in `check` and in the coverage). HTML report in build/reports/cucumber/.
 */
@Suite
@IncludeEngines("cucumber")
@SelectPackages("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.soat.tech.challenge.oficina.os.bdd")
@ConfigurationParameter(key = ANSI_COLORS_DISABLED_PROPERTY_NAME, value = "true")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "summary, html:build/reports/cucumber/cucumber.html")
class WorkOrderFlowFeatureTest
