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

package software.amazon.opentelemetry.appsignals.test.jdbc.operationtests;

public class JdbcOperationTesterProvider {

  private JdbcOperationTesterProvider() {}

  public static JdbcOperationTester getOperationTester(
      DBOperation operation,
      String dbSystem,
      String dbUser,
      String jdbcUrl,
      String dbName,
      String dbTable) {
    switch (operation) {
      case CREATE_DATABASE:
        return new JdbcCreateDatabaseOperationTester(dbSystem, dbUser, jdbcUrl, dbName, dbTable);
      case SELECT:
        return new JdbcSelectOperationTester(dbSystem, dbUser, jdbcUrl, dbName, dbTable);
      default:
        throw new UnsupportedOperationException("No tests for operation: " + operation);
    }
  }
}
