/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.opentelemetry.javaagent.bootstrap;

import io.opentelemetry.javaagent.OpenTelemetryAgent;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.jar.JarFile;

public class AwsAgentBootstrap {

  public static void premain(final String agentArgs, final Instrumentation inst) {
    // CRITICAL: Add the agent JAR to the bootstrap classloader search path FIRST (before any
    // other class loading). This makes the di-bootstrap-bridge classes (embedded at the root of
    // the agent JAR) visible to all classloaders, enabling cross-classloader data sharing between
    // ByteBuddy advice (application classloader) and the Dynamic Instrumentation collectors (agent
    // classloader). A failure here is logged and swallowed so it can never abort agent startup.
    try {
      addAgentJarToBootstrap(inst);
    } catch (Exception e) {
      System.err.println("[AwsAgentBootstrap] Failed to add agent JAR to bootstrap: " + e);
      e.printStackTrace();
    }

    // Store the Instrumentation instance for Dynamic Instrumentation runtime retransformation.
    // Held even when Dynamic Instrumentation is disabled; it is only consumed once the feature is
    // explicitly enabled.
    AwsInstrumentationHolder.setInstrumentation(inst);

    agentmain(agentArgs, inst);
  }

  public static void agentmain(final String agentArgs, final Instrumentation inst) {
    OpenTelemetryAgent.agentmain(agentArgs, inst);
  }

  /**
   * Adds the agent JAR to the bootstrap classloader search path.
   *
   * <p>Follows OpenTelemetry's approach: uses classloader.getResource() instead of
   * getProtectionDomain() to avoid security manager permission checks.
   */
  private static void addAgentJarToBootstrap(Instrumentation inst) throws Exception {
    ClassLoader classLoader = AwsAgentBootstrap.class.getClassLoader();
    if (classLoader == null) {
      classLoader = ClassLoader.getSystemClassLoader();
    }

    String className = AwsAgentBootstrap.class.getName().replace('.', '/') + ".class";
    URL url = classLoader.getResource(className);

    if (url == null || !"jar".equals(url.getProtocol())) {
      throw new IllegalStateException("Could not get agent jar location from url: " + url);
    }

    String resourcePath = url.toURI().getSchemeSpecificPart();
    int protocolSeparatorIndex = resourcePath.indexOf(":");
    int resourceSeparatorIndex = resourcePath.indexOf("!/");

    if (protocolSeparatorIndex == -1 || resourceSeparatorIndex == -1) {
      throw new IllegalStateException("Could not parse agent location from url: " + url);
    }

    String agentPath = resourcePath.substring(protocolSeparatorIndex + 1, resourceSeparatorIndex);
    File javaagentFile = new File(agentPath);

    if (!javaagentFile.isFile()) {
      throw new IllegalStateException(
          "Agent jar location doesn't appear to be a file: " + javaagentFile.getAbsolutePath());
    }

    // false = no manifest verification (faster startup)
    JarFile agentJar = new JarFile(javaagentFile, false);
    inst.appendToBootstrapClassLoaderSearch(agentJar);
  }
}
