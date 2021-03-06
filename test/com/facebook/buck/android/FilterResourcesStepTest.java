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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.FilterResourcesStep.ImageScaler;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.FilteredDirectoryCopier;
import com.facebook.buck.util.Filters;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class FilterResourcesStepTest {

  private final static String first = "/first-path/res";
  private final static String second = "/second-path/res";
  private final static String third = "/third-path/res";

  private final static ImmutableBiMap<String, String> inResDirToOutResDirMap =
      ImmutableBiMap.of(
          first, "/dest/1",
          second, "/dest/2",
          third, "/dest/3");
  private static Set<String> qualifiers = ImmutableSet.of("mdpi", "hdpi", "xhdpi");
  private final String targetDensity = "mdpi";
  private final File baseDestination = new File("/dest");

  private final String scaleSource = getDrawableFile(first, "xhdpi", "other.png");
  private final String scaleDest = getDrawableFile(first, "mdpi", "other.png");

  private String getDrawableFile(String dir, String qualifier, String filename) {
    return MorePaths.newPathInstance(
        new File(dir, String.format("drawable-%s/%s", qualifier, filename))).toString();
  }

  @Test
  public void testFilterResourcesCommand() throws IOException {

    // Mock a ProjectFilesystem. This will be called into by the image downscaling step.
    ProjectFilesystem filesystem = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock
      .expect(filesystem.getFileForRelativePath(EasyMock.<String>anyObject()))
      .andAnswer(new IAnswer<File>(){
          @Override
          public File answer() throws Throwable {
             return new File(String.valueOf(EasyMock.getCurrentArguments()[0]));
          }})
      .anyTimes();
    filesystem.createParentDirs(scaleDest);
    EasyMock.expect(filesystem.deleteFileAtPath(scaleSource)).andReturn(true);
    String scaleSourceDir = new File(scaleSource).getParent();
    EasyMock.expect(filesystem.listFiles(scaleSourceDir)).andReturn(new File[0]);
    EasyMock.expect(filesystem.deleteFileAtPath(scaleSourceDir)).andReturn(true);
    EasyMock.replay(filesystem);

    // Mock an ExecutionContext.
    ExecutionContext context = EasyMock.createMock(ExecutionContext.class);
    ProcessExecutor processExecutor = EasyMock.createMock(ProcessExecutor.class);
    EasyMock.expect(context.getProcessExecutor()).andReturn(processExecutor).anyTimes();
    EasyMock.expect(context.getVerbosity()).andReturn(Verbosity.SILENT).anyTimes();
    EasyMock.expect(context.getProjectFilesystem()).andReturn(filesystem).anyTimes();
    EasyMock.replay(context);

    // Create a mock DrawableFinder, just creates one drawable/density/resource dir.
    FilterResourcesStep.DrawableFinder finder = EasyMock.createMock(
        FilterResourcesStep.DrawableFinder.class);

    // Create mock FilteredDirectoryCopier to find what we're calling on it.
    FilteredDirectoryCopier copier = EasyMock.createMock(FilteredDirectoryCopier.class);
    // We'll want to see what the filtering command passes to the copier.
    Capture<Map<String, String>> dirMapCapture = new Capture<>();
    Capture<Predicate<File>> predCapture = new Capture<>();
    copier.copyDirs(EasyMock.capture(dirMapCapture),
        EasyMock.capture(predCapture));
    EasyMock.replay(copier);

    ImageScaler scaler = EasyMock.createMock(FilterResourcesStep.ImageScaler.class);
    scaler.scale(
        0.5,
        scaleSource,
        scaleDest,
        context
    );

    EasyMock.expect(scaler.isAvailable(context)).andReturn(true);
    EasyMock.replay(scaler);

    FilterResourcesStep command = new FilterResourcesStep(
        inResDirToOutResDirMap,
        /* filterDrawables */ true,
        /* filterStrings */ true,
        copier,
        targetDensity,
        finder,
        scaler);

    EasyMock
      .expect(finder.findDrawables(inResDirToOutResDirMap.keySet()))
      .andAnswer(new IAnswer<Set<String>>() {
        @SuppressWarnings("unchecked")
        @Override
        public Set<String> answer() throws Throwable {
          ImmutableSet.Builder<String> builder = ImmutableSet.builder();
          for (String dir : (Iterable<String>) EasyMock.getCurrentArguments()[0]) {
            for (String qualifier : qualifiers) {
              builder.add(getDrawableFile(dir, qualifier, "some.png"));
            }
          }

          builder.add(scaleSource);

          return builder.build();
        }
      })
      .times(2); // We're calling it in the test as well.

    // Called by the downscaling step.
    EasyMock
      .expect(finder.findDrawables(inResDirToOutResDirMap.values()))
      .andAnswer(new IAnswer<Set<String>>() {
        @SuppressWarnings("unchecked")
        @Override
        public Set<String> answer() throws Throwable {
          ImmutableSet.Builder<String> builder = ImmutableSet.builder();
          for (String dir : (Iterable<String>) EasyMock.getCurrentArguments()[0]) {
            builder.add(getDrawableFile(dir, targetDensity, "some.png"));
          }

          builder.add(scaleSource);
          return builder.build();
        }
      })
      .once();
    EasyMock.replay(finder);

    // We'll use this to verify the source->destination mappings created by the command.
    ImmutableMap.Builder<String, String> dirMapBuilder = ImmutableMap.builder();

    Iterator<String> destIterator = inResDirToOutResDirMap.values().iterator();
    for (String dir : inResDirToOutResDirMap.keySet()) {
      String nextDestination = destIterator.next();
      dirMapBuilder.put(dir, nextDestination);

      // Verify that destination path requirements are observed.
      assertEquals(baseDestination, new File(nextDestination).getParentFile());
    }

    // Execute command.
    command.execute(context);

    // Ensure resources are copied to the right places.
    assertEquals(dirMapBuilder.build(), dirMapCapture.getValue());

    // Ensure the right filter is created.
    Set<String> drawables = finder.findDrawables(inResDirToOutResDirMap.keySet());
    Predicate<File> expectedPred = Filters.createImageDensityFilter(drawables, targetDensity);
    Predicate<File> capturedPred = predCapture.getValue();
    for (String drawablePath : drawables) {
      File drawableFile = new File(drawablePath);
      assertEquals(expectedPred.apply(drawableFile), capturedPred.apply(drawableFile));
    }

    // We shouldn't need the execution context, should call copyDirs once on the copier,
    // and we're calling finder.findDrawables twice.
    EasyMock.verify(copier, context, finder, filesystem, scaler);
  }

  @Test
  public void testNonEnglishStringsPathRegex() {
    assertTrue(matchesRegex("res/values-es/strings.xml"));
    assertFalse(matchesRegex("res/values-/strings.xml"));
    assertTrue(matchesRegex("/res/values-es/strings.xml"));
    assertFalse(matchesRegex("rootres/values-es/strings.xml"));
    assertTrue(matchesRegex("root/res/values-es-rUS/strings.xml"));
  }

  private static boolean matchesRegex(String input) {
    return FilterResourcesStep.NON_ENGLISH_STRING_PATH.matcher(input).matches();
  }
}
