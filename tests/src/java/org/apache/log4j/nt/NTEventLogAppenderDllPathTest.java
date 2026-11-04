/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.log4j.nt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import junit.framework.TestCase;
import org.apache.log4j.LogManager;


/**
 * NTEventLogAppender DLL loading tests (DLL preloading / DLL hijacking).
 *
 * <p>A library loaded with System.loadLibrary is searched for on the library
 * path, so whoever can plant a file named like the NTEventLogAppender DLL in a
 * searched directory gets it loaded into the JVM. Before any test runs, this
 * class plants such files in the first directory of
 * <code>java.library.path</code>, sets <code>log4j.nteventlog.dll.path</code>
 * to the absolute path of an existing native library of the running JVM and
 * initializes NTEventLogAppender. The planted files are not loadable, so the
 * initialization only succeeds when the configured absolute path is loaded and
 * the library path is never searched. This also makes NTEventLogAppender
 * usable by the tests on platforms without the NTEventLogAppender DLL.
 */
public class NTEventLogAppenderDllPathTest extends TestCase {

  private static final String APPENDER_CLASS = "org.apache.log4j.nt.NTEventLogAppender";

  /** Absolute path NTEventLogAppender was configured to load its DLL from. */
  private static String configuredPath;

  /** Error thrown while initializing NTEventLogAppender, null on success. */
  private static Throwable initError;

  static {
    initializeAppender();
  }

  private String originalDllPath;

  public NTEventLogAppenderDllPathTest(final String name) {
    super(name);
  }

  public void setUp() {
    originalDllPath = System.getProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
    clearDllPathProperty();
  }

  public void tearDown() {
    if (originalDllPath != null) {
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, originalDllPath);
    } else {
      clearDllPathProperty();
    }
    LogManager.shutdown();
  }

  /**
   * Removes the DLL path system property
   * (System.clearProperty is not available before Java 5).
   */
  private static void clearDllPathProperty() {
    System.getProperties().remove(NTEventLogAppender.DLL_PATH_PROPERTY);
  }

  /**
   * Initializes NTEventLogAppender with planted libraries on the library path
   * and the DLL path property set to an absolute path.
   * Must run before anything else references NTEventLogAppender.
   */
  private static void initializeAppender() {
    String originalDllPath = System.getProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
    File[] planted = new File[0];
    try {
      planted = plantLibraries();
      File library = findJvmNativeLibrary();
      if (library == null) {
        throw new IllegalStateException("No native library found in " + System.getProperty("java.home"));
      }
      configuredPath = library.getAbsolutePath();
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, configuredPath);
      Class.forName(APPENDER_CLASS, true, NTEventLogAppenderDllPathTest.class.getClassLoader());
    } catch (Throwable t) {
      initError = t;
    } finally {
      if (originalDllPath != null) {
        System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, originalDllPath);
      } else {
        clearDllPathProperty();
      }
      deleteAll(planted);
    }
  }

  /**
   * Plants non-loadable files named like the NTEventLogAppender DLLs in the
   * first directory of java.library.path.
   */
  private static File[] plantLibraries() throws IOException {
    String libraryPath = System.getProperty("java.library.path", "");
    int separator = libraryPath.indexOf(File.pathSeparatorChar);
    File dir = new File(separator < 0 ? libraryPath : libraryPath.substring(0, separator));
    dir.mkdirs();
    String[] names = {
        "NTEventLogAppender." + System.getProperty("os.arch"),
        "NTEventLogAppender" };
    File[] planted = new File[names.length];
    for (int i = 0; i < names.length; i++) {
      planted[i] = new File(dir, System.mapLibraryName(names[i]));
      OutputStream os = new FileOutputStream(planted[i]);
      try {
        os.write("planted library, must never be loaded".getBytes());
      } finally {
        os.close();
      }
    }
    return planted;
  }

  private static void deleteAll(final File[] files) {
    for (int i = 0; i < files.length; i++) {
      if (files[i] != null) {
        files[i].delete();
      }
    }
  }

  /**
   * Finds a native library shipped with the running JVM that is not loaded
   * at startup, so it can be loaded with System.load.
   */
  private static File findJvmNativeLibrary() {
    File javaHome = new File(System.getProperty("java.home"));
    String[] dirs = {
        "lib" + File.separator + System.getProperty("os.arch"), "lib", "bin" };
    String[] names = { "npt", "jaas_unix", "jaas_nt" };
    for (int i = 0; i < names.length; i++) {
      for (int j = 0; j < dirs.length; j++) {
        File candidate = new File(new File(javaHome, dirs[j]), System.mapLibraryName(names[i]));
        if (candidate.isFile()) {
          return candidate.getAbsoluteFile();
        }
      }
    }
    return null;
  }

  /**
   * NTEventLogAppender must load the DLL from the configured absolute path
   * and never fall back to the library path holding the planted libraries.
   */
  public void testConfiguredAbsolutePathIsLoadedInsteadOfPlantedLibrary() {
    assertNotNull("DLL path property was not configured: " + initError, configuredPath);
    if (initError != null) {
      fail("NTEventLogAppender did not load " + configuredPath
          + " but searched the library path: " + initError);
    }
  }

  /**
   * The planted libraries are found by a library path search, so a successful
   * initialization of NTEventLogAppender proves the search was skipped.
   */
  public void testPlantedLibraryIsFoundOnLibraryPath() throws IOException {
    File[] planted = plantLibraries();
    try {
      File target = planted[planted.length - 1];
      try {
        System.loadLibrary("NTEventLogAppender");
        fail("Planted library " + target + " was loaded");
      } catch (UnsatisfiedLinkError e) {
        String message = String.valueOf(e.getMessage());
        assertTrue("Library path search did not reach " + target + ": " + message,
            message.indexOf(target.getCanonicalPath()) >= 0);
      }
    } finally {
      deleteAll(planted);
    }
  }

  /**
   * When the system property is not set, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_notSet() {
    clearDllPathProperty();
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an empty string, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_emptyString() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is whitespace only, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_whitespaceOnly() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "   ");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is a relative path, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_relativePath() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "relative/path/NTEventLogAppender.dll");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an absolute path to a file that does not exist,
   * resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_absoluteButMissing() {
    String missingPath = new File(System.getProperty("java.io.tmpdir"), "no_such_NTEventLogAppender.dll")
        .getAbsolutePath();
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, missingPath);
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an absolute path to an existing file,
   * resolveConfiguredDllPath should return that absolute path.
   */
  public void testResolveConfiguredDllPath_absoluteAndExists() throws IOException {
    File tempFile = File.createTempFile("NTEventLogAppender", ".dll");
    tempFile.deleteOnExit();
    try {
      String absolutePath = tempFile.getAbsolutePath();
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, absolutePath);
      String result = NTEventLogAppender.resolveConfiguredDllPath();
      assertNotNull(result);
      assertEquals(absolutePath, result);
    } finally {
      tempFile.delete();
    }
  }
}
