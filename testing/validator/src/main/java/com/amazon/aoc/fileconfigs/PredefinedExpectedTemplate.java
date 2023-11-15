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

package com.amazon.aoc.fileconfigs;

import java.net.URL;

/**
 * PredefinedExpectedTemplate includes all the built-in expected data templates, which are under
 * resources/expected-data-templates.
 */
public enum PredefinedExpectedTemplate implements FileConfig {
  /** log template, defined in resources. */
  AWSSDK_EXPECTED_LOG("/expected-data-template/awsSdkCallExpectedLog.mustache"),
  HTTP_EXPECTED_LOG("/expected-data-template/httpCallExpectedLog.mustache"),
  REMOTE_SERVICE_EXPECTED_LOG("/expected-data-template/remoteServiceCallExpectedLog.mustache"),
  CLIENT_EXPECTED_LOG("/expected-data-template/clientCallExpectedLog.mustache"),
  /** metric template, defined in resources. */
  HTTP_EXPECTED_METRIC("/expected-data-template/httpCallExpectedMetric.mustache"),
  AWSSDK_EXPECTED_METRIC("/expected-data-template/awsSdkCallExpectedMetric.mustache"),
  REMOTE_SERVICE_EXPECTED_METRIC(
      "/expected-data-template/remoteServiceCallExpectedMetric.mustache"),
  CLIENT_EXPECTED_METRIC("/expected-data-template/clientCallExpectedMetric.mustache"),

  /** trace template, defined in resources. */
  XRAY_SDK_HTTP_EXPECTED_TRACE("/expected-data-template/xraySDKexpectedHTTPTrace.mustache"),
  XRAY_SDK_AWSSDK_EXPECTED_TRACE("/expected-data-template/xraySDKexpectedAWSSDKTrace.mustache"),
  XRAY_SDK_REMOTE_SERVICE_EXPECTED_TRACE(
      "/expected-data-template/xraySDKexpectedREMOTESERVICETrace.mustache"),
  XRAY_SDK_CLIENT_EXPECTED_TRACE("/expected-data-template/xraySDKexpectedCLIENTTrace.mustache"),
  ;

  private String path;

  PredefinedExpectedTemplate(String path) {
    this.path = path;
  }

  @Override
  public URL getPath() {
    return getClass().getResource(path);
  }
}
