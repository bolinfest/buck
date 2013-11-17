/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.json;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.InputStreamConsumer;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * Delegates to buck.py for parsing of buck build files.  Constructed on demand for the
 * parsing phase and must be closed afterward to free up resources.
 */
public class ProjectBuildFileParser implements AutoCloseable {
  /** Path to the buck.py script that is used to evaluate a build file. */
  private static final String PATH_TO_BUCK_PY = System.getProperty("buck.path_to_buck_py",
      "src/com/facebook/buck/parser/buck.py");

  private Process buckPyProcess;

  private BuildFileToJsonParser buckPyStdoutParser;
  private BufferedWriter buckPyStdinWriter;

  private final File projectRoot;
  private final ImmutableSet<Path> ignorePaths;
  private final ImmutableList<String> commonIncludes;
  private final String pythonInterpreter;

  private boolean isServerMode;

  private boolean isInitialized;
  private boolean isClosed;

  public ProjectBuildFileParser(
      ProjectFilesystem projectFilesystem,
      Iterable<String> commonIncludes,
      String pythonInterpreter) {
    this.projectRoot = projectFilesystem.getProjectRoot();
    this.ignorePaths = projectFilesystem.getIgnorePaths();
    this.commonIncludes = ImmutableList.copyOf(commonIncludes);
    this.pythonInterpreter = Preconditions.checkNotNull(pythonInterpreter);

    // Default to server mode unless explicitly unset internally.
    setServerMode(true);
  }

  /**
   * Sets whether buck.py will use --server mode.  Server mode communicates via
   * stdin/stdout to accept new BUCK files to parse in a long running fashion.  It
   * also changes the stdout format so that output has an extra layer of structure
   * sufficient to communicate state and coordinate on individual BUCK files
   * submitted.
   * <p>
   * Note that you must not invoke this method after initialization.
   */
  private void setServerMode(boolean isServerMode) {
    ensureNotClosed();
    ensureNotInitialized();

    this.isServerMode = isServerMode;
  }

  private void ensureNotClosed() {
    Preconditions.checkState(!isClosed);
  }

  private void ensureNotInitialized() {
    Preconditions.checkState(!isInitialized);
  }

  /**
   * Initialization on demand moves around the performance impact of creating the Jython
   * interpreter to when parsing actually begins.  This makes it easier to attribute this time
   * to the actual parse phase.
   */
  private void initIfNeeded() throws IOException {
    ensureNotClosed();
    if (!isInitialized) {
      init();
      isInitialized = true;
    }
  }

  /**
   * Initialize the parser, starting buck.py.
   */
  private void init() throws IOException {
    ProcessBuilder processBuilder = new ProcessBuilder(buildArgs());
    buckPyProcess = processBuilder.start();

    OutputStream stdin = buckPyProcess.getOutputStream();
    InputStream stderr = buckPyProcess.getErrorStream();

    Thread stderrConsumer = Threads.namedThread(
        ProjectBuildFileParser.class.getSimpleName(),
        new InputStreamConsumer(stderr,
            System.err,
            new Ansi(Platform.detect())));
    stderrConsumer.start();

    buckPyStdinWriter = new BufferedWriter(new OutputStreamWriter(stdin));

    // TODO(mbolin): Ensure that the Reader gets closed.
    Reader reader = new InputStreamReader(buckPyProcess.getInputStream(), Charsets.UTF_8);
    buckPyStdoutParser = new BuildFileToJsonParser(reader, isServerMode);
  }

  private ImmutableList<String> buildArgs() {
    // Invoking buck.py and read JSON-formatted build rules from its stdout.
    ImmutableList.Builder<String> argBuilder = ImmutableList.builder();

    argBuilder.add(pythonInterpreter);

    // Ask python to unbuffer stdout so that we can coordinate based on the output as it is
    // produced.
    argBuilder.add("-u");

    argBuilder.add(PATH_TO_BUCK_PY);

    if (isServerMode) {
      // Provide BUCK files to parse via buck.py's stdin.
      argBuilder.add("--server");
    }

    argBuilder.add("--project_root", projectRoot.getAbsolutePath());

    // Add the --include flags.
    for (String include : commonIncludes) {
      argBuilder.add("--include");
      argBuilder.add(include);
    }

    for (Path path : ignorePaths) {
      argBuilder.add("--ignore_path");
      argBuilder.add(path.toString());
    }

    return argBuilder.build();
  }

  /**
   * Create, parse and destroy the parser in one step for an entire project.  This should
   * only be used when the tree must be parsed without a specific target to be built or
   * otherwise operated upon.
   */
  public static List<Map<String, Object>> getAllRulesInProject(
      ProjectBuildFileParserFactory factory,
      Iterable<String> includes)
      throws BuildFileParseException {
    try (ProjectBuildFileParser buildFileParser = factory.createParser(includes)) {
      buildFileParser.setServerMode(false);
      return buildFileParser.getAllRulesInternal(Optional.<String>absent());
    } catch (IOException e) {
      throw BuildFileParseException.createForGenericBuildFileParseError(e);
    }
  }

  /**
   * Collect all rules from a particular build file.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  public List<Map<String, Object>> getAllRules(String buildFile)
      throws BuildFileParseException {
    List<Map<String, Object>> result = getAllRulesAndMetaRules(buildFile);

    // Strip out the __includes meta rule, which is the last rule.
    return Collections.unmodifiableList(result.subList(0, result.size() - 1));
  }

  /**
   * Collect all rules from a particular build file, along with meta rules about the rules, for
   * example which build files the rules depend on.
   *
   * @param buildFile should be an absolute path to a build file. Must have rootPath as its prefix.
   */
  public List<Map<String, Object>> getAllRulesAndMetaRules(String buildFile)
      throws BuildFileParseException {
    try {
      return getAllRulesInternal(Optional.of(buildFile));
    } catch (IOException e) {
      throw BuildFileParseException.createForBuildFileParseError(buildFile, e);
    }
  }

  @VisibleForTesting
  protected List<Map<String, Object>> getAllRulesInternal(Optional<String> buildFile)
      throws IOException {
    ensureNotClosed();
    initIfNeeded();

    // When in server mode, we require a build file.  When not in server mode, we
    // cannot accept a build file.  Pretty stupid, actually.  Consider fixing this.
    Preconditions.checkState(buildFile.isPresent() == isServerMode);

    if (buildFile.isPresent()) {
      buckPyStdinWriter.write(buildFile.get());
      buckPyStdinWriter.newLine();
      buckPyStdinWriter.flush();
    }

    return buckPyStdoutParser.nextRules();
  }

  @Override
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void close() throws BuildFileParseException {
    if (isClosed) {
      return;
    }

    try {
      if (isInitialized) {
        if (isServerMode) {
          // Allow buck.py to terminate gracefully.
          try {
            buckPyStdinWriter.close();
          } catch (IOException e) {
            // Safe to ignore since we've already flushed everything we wanted
            // to write.
          }
        }

        try {
          int exitCode = buckPyProcess.waitFor();
          if (exitCode != 0) {
            BuildFileParseException.createForUnknownParseError(
                String.format("Parser did not exit cleanly (exit code: %d)", exitCode));
          }
        } catch (InterruptedException e) {
          throw Throwables.propagate(e);
        }
      }
    } finally {
      isClosed = true;
    }
  }
}
