/*
 * Sonar C++ Plugin (Community)
 * Copyright (C) 2010-2020 SonarOpenCommunity
 * http://github.com/SonarOpenCommunity/sonar-cxx
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.cxx.sensors.tests.xunit;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamSource;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.config.Configuration;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.cxx.sensors.utils.CxxReportSensor;
import org.sonar.cxx.sensors.utils.CxxUtils;
import org.sonar.cxx.sensors.utils.EmptyReportException;
import org.sonar.cxx.sensors.utils.StaxParser;

/**
 * {@inheritDoc}
 */
public class CxxXunitSensor extends CxxReportSensor {

  public static final String REPORT_PATH_KEY = "sonar.cxx.xunit.reportPath";
  public static final String XSLT_URL_KEY = "sonar.cxx.xunit.xsltURL";
  private static final Logger LOG = Loggers.get(CxxXunitSensor.class);

  private final String xsltURL;

  /**
   * CxxXunitSensor
   *
   * @param config sensor configuration
   */
  public CxxXunitSensor(Configuration config) {
    xsltURL = config.get(XSLT_URL_KEY).orElse(null);
  }

  public static List<PropertyDefinition> properties() {
    return Collections.unmodifiableList(Arrays.asList(
      PropertyDefinition.builder(REPORT_PATH_KEY)
        .name("Unit test execution report(s)")
        .description("Path to unit test execution report(s), relative to projects root."
                       + " See <a href='https://github.com/SonarOpenCommunity/sonar-cxx/wiki/Get-test-execution-metrics'>"
                     + "here</a> for supported formats." + USE_ANT_STYLE_WILDCARDS)
        .category("CXX External Analyzers")
        .subCategory("xUnit Test")
        .onQualifiers(Qualifiers.PROJECT)
        .multiValues(true)
        .build(),
      PropertyDefinition.builder(XSLT_URL_KEY)
        .name("XSLT transformer")
        .description("By default, the unit test execution reports are expected to be in the JUnitReport format."
                       + " To import a report in an other format, set this property to an URL to a XSLT stylesheet which is "
                     + "able to perform the according transformation.")
        .category("CXX External Analyzers")
        .subCategory("xUnit Test")
        .onQualifiers(Qualifiers.PROJECT)
        .build()
    ));
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("CXX xUnit Test report import")
      //.onlyOnLanguage(getLanguage().getKey())
      .onlyWhenConfiguration(conf -> conf.hasKey(REPORT_PATH_KEY));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void executeImpl(SensorContext context) {
    LOG.debug("Root module imports test metrics: Module Key = '{}'", context.project());

    try {
      List<File> reports = getReports(context.config(), context.fileSystem().baseDir(), REPORT_PATH_KEY);
      if (!reports.isEmpty()) {
        XunitReportParser parserHandler = parseReport(reports);
        List<TestCase> testcases = parserHandler.getTestCases();

        LOG.info("Parsing 'xUnit' format");
        simpleMode(context, testcases);
      } else {
        LOG.debug("No reports found, nothing to process");
      }
    } catch (IOException | TransformerException | XMLStreamException e) {
      var msg = new StringBuilder(256)
        .append("Cannot feed the data into SonarQube, details: '")
        .append(e)
        .append("'")
        .toString();
      LOG.error(msg);
      CxxUtils.validateRecovery(e, context.config());
    }
  }

  /**
   * @param reports
   * @return
   * @throws XMLStreamException
   * @throws IOException
   * @throws TransformerException
   */
  private XunitReportParser parseReport(List<File> reports)
    throws XMLStreamException, IOException, TransformerException {
    var parserHandler = new XunitReportParser();
    var parser = new StaxParser(parserHandler, false);
    for (var report : reports) {
      LOG.info("Processing report '{}'", report);
      try {
        parser.parse(transformReport(report));
      } catch (EmptyReportException e) {
        LOG.warn("The report '{}' seems to be empty, ignoring.", report);
        LOG.debug("{}", e);
      }
    }
    return parserHandler;
  }

  private void simpleMode(final SensorContext context, List<TestCase> testcases) {

    int testsCount = 0;
    int testsSkipped = 0;
    int testsErrors = 0;
    int testsFailures = 0;
    long testsTime = 0;
    for (var tc : testcases) {
      if (tc.isSkipped()) {
        testsSkipped++;
      } else if (tc.isFailure()) {
        testsFailures++;
      } else if (tc.isError()) {
        testsErrors++;
      }
      testsCount++;
      testsTime += tc.getTime();
    }
    testsCount -= testsSkipped;

    if (testsCount > 0) {

      try {
        saveMetric(context, CoreMetrics.TESTS, testsCount);
      } catch (IllegalArgumentException ex) {
        LOG.error("Cannot save measure TESTS : '{}', ignoring measure", ex.getMessage());
        CxxUtils.validateRecovery(ex, context.config());
      }

      try {
        saveMetric(context, CoreMetrics.TEST_ERRORS, testsErrors);
      } catch (IllegalArgumentException ex) {
        LOG.error("Cannot save measure TEST_ERRORS : '{}', ignoring measure", ex.getMessage());
        CxxUtils.validateRecovery(ex, context.config());
      }

      try {
        saveMetric(context, CoreMetrics.TEST_FAILURES, testsFailures);
      } catch (IllegalArgumentException ex) {
        LOG.error("Cannot save measure TEST_FAILURES : '{}', ignoring measure", ex.getMessage());
        CxxUtils.validateRecovery(ex, context.config());
      }

      try {
        saveMetric(context, CoreMetrics.SKIPPED_TESTS, testsSkipped);
      } catch (IllegalArgumentException ex) {
        LOG.error("Cannot save measure SKIPPED_TESTS : '{}', ignoring measure", ex.getMessage());
        CxxUtils.validateRecovery(ex, context.config());
      }

      try {
        saveMetric(context, CoreMetrics.TEST_EXECUTION_TIME, testsTime);
      } catch (IllegalArgumentException ex) {
        LOG.error("Cannot save measure TEST_EXECUTION_TIME : '{}', ignoring measure", ex.getMessage());
        CxxUtils.validateRecovery(ex, context.config());
      }
    } else {
      LOG.debug("The reports contain no testcases");
    }
  }

  File transformReport(File report)
    throws java.io.IOException, javax.xml.transform.TransformerException {
    File transformed = report;
    if (xsltURL != null && report.length() > 0) {
      LOG.debug("Transforming the report using xslt '{}'", xsltURL);
      InputStream inputStream = this.getClass().getResourceAsStream("/xsl/" + xsltURL);
      if (inputStream == null) {
        LOG.debug("Transforming: try to access external XSLT via URL");
        var url = new URL(xsltURL);
        inputStream = url.openStream();
      }

      var xsl = new StreamSource(inputStream);
      transformed = new File(report.getAbsolutePath() + ".after_xslt");
      CxxUtils.transformFile(xsl, report, transformed);
    } else {
      LOG.debug("Transformation skipped: no xslt given");
    }

    return transformed;
  }

  private <T extends Serializable> void saveMetric(SensorContext context, Metric<T> metric, T value) {
    context.<T>newMeasure()
      .withValue(value)
      .forMetric(metric)
      .on(context.project())
      .save();
  }

}
