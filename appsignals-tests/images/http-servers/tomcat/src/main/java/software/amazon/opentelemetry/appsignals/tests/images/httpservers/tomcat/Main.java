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

package software.amazon.opentelemetry.appsignals.tests.images.httpservers.tomcat;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Tomcat;

public class Main {
  public static void main(String[] args) throws Exception {

    Tomcat tomcat = new Tomcat();

    // The port that we should run on can be set into an environment variable
    // Look for that variable and default to 8080 if it isn't there.
    String webPort = System.getenv("PORT");
    if (webPort == null || webPort.isEmpty()) {
      webPort = "8080";
    }

    StandardContext ctx = (StandardContext) tomcat.addContext("", null);
    tomcat.addServlet(ctx, "SuccessServlet", new SuccessServlet());
    tomcat.addServlet(ctx, "FaultServlet", new FaultServlet());
    tomcat.addServlet(ctx, "ErrorServlet", new ErrorServlet());
    tomcat.addServlet(ctx, "RouteServlet", new RouteServlet());
    ctx.addServletMappingDecoded("/success", "SuccessServlet");
    ctx.addServletMappingDecoded("/fault", "FaultServlet");
    ctx.addServletMappingDecoded("/error", "ErrorServlet");
    ctx.addServletMappingDecoded("/users/*", "RouteServlet");
    tomcat.setPort(Integer.valueOf(webPort));
    tomcat.getConnector();
    tomcat.start();
    tomcat.getServer().await();
  }
}
