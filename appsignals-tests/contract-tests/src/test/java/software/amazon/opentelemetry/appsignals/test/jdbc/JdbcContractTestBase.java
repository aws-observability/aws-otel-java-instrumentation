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

package software.amazon.opentelemetry.appsignals.test.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import software.amazon.opentelemetry.appsignals.test.base.ContractTestBase;
import software.amazon.opentelemetry.appsignals.test.jdbc.operationtests.DBOperation;
import software.amazon.opentelemetry.appsignals.test.jdbc.operationtests.JdbcOperationTester;
import software.amazon.opentelemetry.appsignals.test.jdbc.operationtests.JdbcOperationTesterProvider;
import software.amazon.opentelemetry.appsignals.test.utils.AppSignalsConstants;

public abstract class JdbcContractTestBase extends ContractTestBase {
  protected static final String DB_NAME = "testdb";
  protected static final String DB_USER = "root";
  protected static final String DB_PASSWORD = "password";
  protected static final String DB_RESOURCE_TYPE = "DB::Connection";

  @Override
  protected String getApplicationImageName() {
    return "aws-appsignals-tests-jdbc-app";
  }

  @Override
  protected String getApplicationWaitPattern() {
    return ".*Application Ready.*";
  }

  protected void assertSuccess(
      String dbSystem,
      DBOperation dbOperation,
      String dbUser,
      String dbName,
      String jdbcUrl,
      String type,
      String identifier) {
    var path = "success/" + dbOperation.name();
    var method = "GET";
    var otelStatusCode = "STATUS_CODE_UNSET";
    var dbSqlTable = "employee";
    var otelApplicationImageName = getApplicationOtelServiceName();
    JdbcOperationTester operationTester =
        JdbcOperationTesterProvider.getOperationTester(
            dbOperation, dbSystem, dbUser, jdbcUrl, dbName, dbSqlTable);
    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isSuccess()).isTrue();

    var traces = mockCollectorClient.getTraces();
    operationTester.assertAwsSpanAttributes(
        traces, method, path, type, identifier, otelApplicationImageName);
    operationTester.assertSemanticConventionsSpanAttributes(traces, otelStatusCode);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    operationTester.assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.LATENCY_METRIC,
        5000.0,
        type,
        identifier,
        otelApplicationImageName);
    operationTester.assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.ERROR_METRIC,
        0.0,
        type,
        identifier,
        otelApplicationImageName);
    operationTester.assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.FAULT_METRIC,
        0.0,
        type,
        identifier,
        otelApplicationImageName);
  }

  protected void assertFault(
      String dbSystem,
      DBOperation dbOperation,
      String dbUser,
      String dbName,
      String jdbcUrl,
      String type,
      String identifier) {
    var path = "fault/" + dbOperation;
    var method = "GET";
    var otelStatusCode = "STATUS_CODE_ERROR";
    var dbSqlTable = "userrr";
    var otelApplicationImageName = getApplicationOtelServiceName();
    JdbcOperationTester operationTester =
        JdbcOperationTesterProvider.getOperationTester(
            dbOperation, dbSystem, dbUser, jdbcUrl, dbName, dbSqlTable);
    var response = appClient.get(path).aggregate().join();
    assertThat(response.status().isServerError()).isTrue();

    var traces = mockCollectorClient.getTraces();
    operationTester.assertAwsSpanAttributes(
        traces, method, path, type, identifier, otelApplicationImageName);
    operationTester.assertSemanticConventionsSpanAttributes(traces, otelStatusCode);

    var metrics =
        mockCollectorClient.getMetrics(
            Set.of(
                AppSignalsConstants.LATENCY_METRIC,
                AppSignalsConstants.ERROR_METRIC,
                AppSignalsConstants.FAULT_METRIC));
    operationTester.assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.LATENCY_METRIC,
        5000.0,
        type,
        identifier,
        otelApplicationImageName);
    operationTester.assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.ERROR_METRIC,
        0.0,
        type,
        identifier,
        otelApplicationImageName);
    operationTester.assertMetricAttributes(
        metrics,
        method,
        path,
        AppSignalsConstants.FAULT_METRIC,
        1.0,
        type,
        identifier,
        otelApplicationImageName);
  }
}
