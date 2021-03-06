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
package org.sonar.plugins.cxx;

import com.sonar.sslr.api.Grammar;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.sonar.api.PropertyType;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.config.PropertyDefinition;
import org.sonar.api.internal.google.common.base.Splitter;
import org.sonar.api.internal.google.common.collect.Iterables;
import org.sonar.api.issue.NoSonarFilter;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.scanner.sensor.ProjectSensor;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.cxx.CxxAstScanner;
import org.sonar.cxx.CxxMetrics;
import org.sonar.cxx.CxxSquidConfiguration;
import org.sonar.cxx.api.CxxMetric;
import org.sonar.cxx.checks.CheckList;
import org.sonar.cxx.sensors.utils.CxxReportSensor;
import org.sonar.cxx.sensors.utils.JsonCompilationDatabase;
import org.sonar.cxx.sensors.visitors.CxxCpdVisitor;
import org.sonar.cxx.sensors.visitors.CxxFileLinesVisitor;
import org.sonar.cxx.sensors.visitors.CxxHighlighterVisitor;
import org.sonar.cxx.visitors.MultiLocatitionSquidCheck;
import org.sonar.squidbridge.AstScanner;
import org.sonar.squidbridge.SquidAstVisitor;
import org.sonar.squidbridge.api.SourceCode;
import org.sonar.squidbridge.api.SourceFile;
import org.sonar.squidbridge.indexer.QueryByType;

/**
 * {@inheritDoc}
 */
public class CxxSquidSensor implements ProjectSensor {

  public static final String DEFINES_KEY = "sonar.cxx.defines";
  public static final String INCLUDE_DIRECTORIES_KEY = "sonar.cxx.includeDirectories";
  public static final String ERROR_RECOVERY_KEY = "sonar.cxx.errorRecoveryEnabled";
  public static final String FORCE_INCLUDE_FILES_KEY = "sonar.cxx.forceIncludes";
  public static final String JSON_COMPILATION_DATABASE_KEY = "sonar.cxx.jsonCompilationDatabase";

  /**
   * the following settings are in use by the feature to read configuration settings from the VC compiler report
   */
  public static final String REPORT_PATH_KEY = "sonar.cxx.msbuild.reportPath";
  public static final String REPORT_CHARSET_DEF = "sonar.cxx.msbuild.charset";
  public static final String DEFAULT_CHARSET_DEF = StandardCharsets.UTF_8.name();

  private static final String USE_ANT_STYLE_WILDCARDS
                                = " Use <a href='https://ant.apache.org/manual/dirtasks.html'>Ant-style wildcards</a> if neccessary.";

  /**
   * Key of the file suffix parameter
   */
  public static final String API_FILE_SUFFIXES_KEY = "sonar.cxx.api.file.suffixes";

  /**
   * Default API files knows suffixes
   */
  public static final String API_DEFAULT_FILE_SUFFIXES = ".hxx,.hpp,.hh,.h";

  public static final String FUNCTION_COMPLEXITY_THRESHOLD_KEY = "sonar.cxx.funccomplexity.threshold";
  public static final String FUNCTION_SIZE_THRESHOLD_KEY = "sonar.cxx.funcsize.threshold";

  private final FileLinesContextFactory fileLinesContextFactory;
  private final CxxChecks checks;
  private final NoSonarFilter noSonarFilter;

  private SensorContext context;

  private static final Logger LOG = Loggers.get(CxxSquidSensor.class);

  /**
   * {@inheritDoc}
   */
  public CxxSquidSensor(FileLinesContextFactory fileLinesContextFactory,
                        CheckFactory checkFactory,
                        NoSonarFilter noSonarFilter) {
    this(fileLinesContextFactory, checkFactory, noSonarFilter, null);
  }

  /**
   * {@inheritDoc}
   */
  public CxxSquidSensor(FileLinesContextFactory fileLinesContextFactory,
                        CheckFactory checkFactory,
                        NoSonarFilter noSonarFilter,
                        @Nullable CustomCxxRulesDefinition[] customRulesDefinition) {
    this.checks = CxxChecks.createCxxCheck(checkFactory)
      .addChecks(CheckList.REPOSITORY_KEY, CheckList.getChecks())
      .addCustomChecks(customRulesDefinition);
    this.fileLinesContextFactory = fileLinesContextFactory;
    this.noSonarFilter = noSonarFilter;
  }

  public static List<PropertyDefinition> properties() {
    return Collections.unmodifiableList(Arrays.asList(
      PropertyDefinition.builder(INCLUDE_DIRECTORIES_KEY)
        .multiValues(true)
        .name("Include directories")
        .description("Comma-separated list of directories to search the included files in. "
                       + "May be defined either relative to projects root or absolute.")
        .category("CXX")
        .subCategory("(2) Defines & Includes")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(FORCE_INCLUDE_FILES_KEY)
        .multiValues(true)
        .category("CXX")
        .subCategory("(2) Defines & Includes")
        .name("Force includes")
        .description("Comma-separated list of files which should to be included implicitly at the "
                       + "beginning of each source file.")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(DEFINES_KEY)
        .name("Default macros")
        .description("Additional macro definitions (one per line) to use when analysing the source code. Use to provide"
                       + "macros which cannot be resolved by other means."
                       + " Use the 'force includes' setting to inject more complex, multi-line macros.")
        .category("CXX")
        .subCategory("(2) Defines & Includes")
        .onQualifiers(Qualifiers.PROJECT)
        .type(PropertyType.TEXT)
        .build(),
      PropertyDefinition.builder(ERROR_RECOVERY_KEY)
        .defaultValue(Boolean.TRUE.toString())
        .name("Parse error recovery")
        .description("Defines mode for error handling of report files and parsing errors. `False' (strict) breaks after"
                       + " an error or 'True' (tolerant=default) continues. See <a href='https://github.com/SonarOpenCommunity/"
                     + "sonar-cxx/wiki/Supported-configuration-properties#sonarcxxerrorrecoveryenabled'>"
                       + "sonar.cxx.errorRecoveryEnabled</a> for a complete description.")
        .category("CXX")
        .subCategory("(1) General")
        .onQualifiers(Qualifiers.PROJECT)
        .type(PropertyType.BOOLEAN)
        .build(),
      PropertyDefinition.builder(REPORT_PATH_KEY)
        .name("Path(s) to MSBuild log(s)")
        .description("Extract includes, defines and compiler options from the build log. This works only"
                       + " if the produced log during compilation adds enough information (MSBuild verbosity set to"
                       + " detailed or diagnostic)."
                       + USE_ANT_STYLE_WILDCARDS)
        .category("CXX")
        .subCategory("(2) Defines & Includes")
        .onQualifiers(Qualifiers.PROJECT)
        .multiValues(true)
        .build(),
      PropertyDefinition.builder(REPORT_CHARSET_DEF)
        .defaultValue(DEFAULT_CHARSET_DEF)
        .name("MSBuild log encoding")
        .description("The encoding to use when reading a MSBuild log. Leave empty to use default UTF-8.")
        .category("CXX")
        .subCategory("(2) Defines & Includes")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(JSON_COMPILATION_DATABASE_KEY)
        .category("CXX")
        .subCategory("(2) Defines & Includes")
        .name("JSON Compilation Database")
        .description("JSON Compilation Database file to use as specification for what defines and includes should be "
                       + "used for source files.")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(API_FILE_SUFFIXES_KEY)
        .defaultValue(API_DEFAULT_FILE_SUFFIXES)
        .name("Pulic API file suffixes")
        .multiValues(true)
        .description("Comma-separated list of suffixes for files that should be searched for API comments."
                       + " To not filter, leave the list empty.")
        .category("CXX")
        .subCategory("(3) Metrics")
        .onQualifiers(Qualifiers.PROJECT)
        .build(),
      PropertyDefinition.builder(FUNCTION_COMPLEXITY_THRESHOLD_KEY)
        .defaultValue("10")
        .name("Cyclomatic complexity threshold")
        .description("Cyclomatic complexity threshold used to classify a function as complex")
        .category("CXX")
        .subCategory("(3) Metrics")
        .onQualifiers(Qualifiers.PROJECT)
        .type(PropertyType.INTEGER)
        .build(),
      PropertyDefinition.builder(FUNCTION_SIZE_THRESHOLD_KEY)
        .defaultValue("20")
        .name("Function size threshold")
        .description("Function size threshold to consider a function to be too big")
        .category("CXX")
        .subCategory("(3) Metrics")
        .onQualifiers(Qualifiers.PROJECT)
        .type(PropertyType.INTEGER)
        .build()
    ));
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .name("CXX")
      .onlyOnLanguage("c++")
      .onlyOnFileType(InputFile.Type.MAIN);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute(SensorContext context) {

    this.context = context;

    var visitors = new ArrayList<SquidAstVisitor<Grammar>>(checks.all());
    visitors.add(new CxxHighlighterVisitor(context));
    visitors.add(new CxxFileLinesVisitor(context, fileLinesContextFactory));

    visitors.add(new CxxCpdVisitor(context));

    CxxSquidConfiguration squidConfig = createConfiguration();
    AstScanner<Grammar> scanner = CxxAstScanner.create(squidConfig, visitors.toArray(
                                                       new SquidAstVisitor[visitors.size()]));

    Iterable<InputFile> inputFiles = context.fileSystem().inputFiles(
      context.fileSystem().predicates().and(context.fileSystem().predicates().hasLanguage("c++"),
                                            context.fileSystem().predicates().hasType(InputFile.Type.MAIN)));

    var files = new ArrayList<File>();
    for (var file : inputFiles) {
      files.add(new File(file.uri().getPath()));
    }

    scanner.scanFiles(files);

    Collection<SourceCode> squidSourceFiles = scanner.getIndex().search(new QueryByType(SourceFile.class));
    save(squidSourceFiles);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName();
  }

  private String[] getStringLinesOption(String key) {
    Pattern EOL_PATTERN = Pattern.compile("\\R");
    Optional<String> value = context.config().get(key);
    if (value.isPresent()) {
      return EOL_PATTERN.split(value.get(), -1);
    }
    return new String[0];
  }

  private CxxSquidConfiguration createConfiguration() {
    var squidConfig = new CxxSquidConfiguration(context.fileSystem().encoding());
    squidConfig.setBaseDir(context.fileSystem().baseDir().getAbsolutePath());
    String[] lines = getStringLinesOption(DEFINES_KEY);
    squidConfig.setDefines(lines);
    squidConfig.setIncludeDirectories(context.config().getStringArray(INCLUDE_DIRECTORIES_KEY));
    squidConfig.setErrorRecoveryEnabled(context.config().getBoolean(ERROR_RECOVERY_KEY).orElse(Boolean.FALSE));
    squidConfig.setForceIncludeFiles(context.config().getStringArray(FORCE_INCLUDE_FILES_KEY));

    String[] suffixes = Arrays.stream(context.config().getStringArray(API_FILE_SUFFIXES_KEY))
      .filter(s -> s != null && !s.trim().isEmpty()).toArray(String[]::new);
    if (suffixes.length == 0) {
      suffixes = Iterables.toArray(Splitter.on(',').split(API_DEFAULT_FILE_SUFFIXES), String.class);
    }
    squidConfig.setPublicApiFileSuffixes(suffixes);

    squidConfig.setFunctionComplexityThreshold(context.config().getInt(FUNCTION_COMPLEXITY_THRESHOLD_KEY).orElse(10));
    squidConfig.setFunctionSizeThreshold(context.config().getInt(FUNCTION_SIZE_THRESHOLD_KEY).orElse(20));

    squidConfig.setJsonCompilationDatabaseFile(context.config().get(JSON_COMPILATION_DATABASE_KEY)
      .orElse(null));

    if (squidConfig.getJsonCompilationDatabaseFile() != null) {
      try {
        JsonCompilationDatabase.parse(squidConfig, new File(squidConfig.getJsonCompilationDatabaseFile()));
      } catch (IOException e) {
        LOG.debug("Cannot access Json DB File: {}", e);
      }
    }

    final String[] buildLogPaths = context.config().getStringArray(REPORT_PATH_KEY);
    final boolean buildLogPathsDefined = buildLogPaths != null && buildLogPaths.length != 0;
    if (buildLogPathsDefined) {
      List<File> reports = CxxReportSensor.getReports(context.config(), context.fileSystem().baseDir(), REPORT_PATH_KEY);
      squidConfig.setCompilationPropertiesWithBuildLog(reports, "Visual C++",
                                                       context.config().get(REPORT_CHARSET_DEF).orElse(
                                                         DEFAULT_CHARSET_DEF));
    }

    return squidConfig;
  }

  private void save(Collection<SourceCode> squidSourceFiles) {
    // don't publish metrics on modules, which were not analyzed
    // otherwise hierarchical multi-module projects will contain wrong metrics ( == 0)
    // see also AggregateMeasureComputer
    if (squidSourceFiles.isEmpty()) {
      return;
    }

    for (var squidSourceFile : squidSourceFiles) {
      SourceFile squidFile = (SourceFile) squidSourceFile;
      var ioFile = new File(squidFile.getKey());
      InputFile inputFile = context.fileSystem().inputFile(context.fileSystem().predicates().is(ioFile));

      saveMeasures(inputFile, squidFile);
      saveViolations(inputFile, squidFile);
    }
  }

  private void saveMeasures(InputFile inputFile, SourceFile squidFile) {

    // NOSONAR
    noSonarFilter.noSonarInFile(inputFile, squidFile.getNoSonarTagLines());

    // CORE METRICS
    saveMetric(inputFile, CoreMetrics.NCLOC, squidFile.getInt(CxxMetric.LINES_OF_CODE));
    saveMetric(inputFile, CoreMetrics.STATEMENTS, squidFile.getInt(CxxMetric.STATEMENTS));
    saveMetric(inputFile, CoreMetrics.FUNCTIONS, squidFile.getInt(CxxMetric.FUNCTIONS));
    saveMetric(inputFile, CoreMetrics.CLASSES, squidFile.getInt(CxxMetric.CLASSES));
    saveMetric(inputFile, CoreMetrics.COMPLEXITY, squidFile.getInt(CxxMetric.COMPLEXITY));
    saveMetric(inputFile, CoreMetrics.COGNITIVE_COMPLEXITY, squidFile.getInt(CxxMetric.COGNITIVE_COMPLEXITY));
    saveMetric(inputFile, CoreMetrics.COMMENT_LINES, squidFile.getInt(CxxMetric.COMMENT_LINES));

    // CUSTOM METRICS
    //
    // non-core metrics are not aggregated automatically, see AggregateMeasureComputer
    // below metrics are calculated by means of DensityMeasureComputer
    //
    // 1. PUBLIC API
    saveMetric(inputFile, CxxMetrics.PUBLIC_API, squidFile.getInt(CxxMetric.PUBLIC_API));
    saveMetric(inputFile, CxxMetrics.PUBLIC_UNDOCUMENTED_API, squidFile.getInt(CxxMetric.PUBLIC_UNDOCUMENTED_API));

    // 2. FUNCTION COMPLEXITY
    saveMetric(inputFile, CxxMetrics.COMPLEX_FUNCTIONS, squidFile.getInt(CxxMetric.COMPLEX_FUNCTIONS));
    saveMetric(inputFile, CxxMetrics.COMPLEX_FUNCTIONS_LOC, squidFile.getInt(CxxMetric.COMPLEX_FUNCTIONS_LOC));

    // 3. FUNCTION SIZE
    saveMetric(inputFile, CxxMetrics.LOC_IN_FUNCTIONS, squidFile.getInt(CxxMetric.LOC_IN_FUNCTIONS));
    saveMetric(inputFile, CxxMetrics.BIG_FUNCTIONS, squidFile.getInt(CxxMetric.BIG_FUNCTIONS));
    saveMetric(inputFile, CxxMetrics.BIG_FUNCTIONS_LOC, squidFile.getInt(CxxMetric.BIG_FUNCTIONS_LOC));
  }

  private void saveViolations(InputFile inputFile, SourceFile squidFile) {
    if (squidFile.hasCheckMessages()) {
      for (var message : squidFile.getCheckMessages()) {
        int line = 1;
        if (message.getLine() != null && message.getLine() > 0) {
          line = message.getLine();
        }

        NewIssue newIssue = context.newIssue().forRule(
          RuleKey.of(CheckList.REPOSITORY_KEY,
                     checks.ruleKey(
                       (SquidAstVisitor<Grammar>) message.getCheck())
                       .rule()));
        NewIssueLocation location = newIssue.newLocation().on(inputFile).at(inputFile.selectLine(line))
          .message(message.getText(Locale.ENGLISH));

        newIssue.at(location);
        newIssue.save();
      }
    }

    if (MultiLocatitionSquidCheck.hasMultiLocationCheckMessages(squidFile)) {
      for (var issue : MultiLocatitionSquidCheck.getMultiLocationCheckMessages(squidFile)) {
        final NewIssue newIssue = context.newIssue()
          .forRule(RuleKey.of(CheckList.REPOSITORY_KEY, issue.getRuleId()));
        int locationNr = 0;
        for (var location : issue.getLocations()) {
          final Integer line = Integer.valueOf(location.getLine());
          final NewIssueLocation newIssueLocation = newIssue.newLocation().on(inputFile).at(inputFile.selectLine(line))
            .message(location.getInfo());
          if (locationNr == 0) {
            newIssue.at(newIssueLocation);
          } else {
            newIssue.addLocation(newIssueLocation);
          }
          ++locationNr;
        }
        newIssue.save();
      }
      MultiLocatitionSquidCheck.eraseMultilineCheckMessages(squidFile);
    }
  }

  private <T extends Serializable> void saveMetric(InputFile file, Metric<T> metric, T value) {
    context.<T>newMeasure()
      .withValue(value)
      .forMetric(metric)
      .on(file)
      .save();
  }

}
