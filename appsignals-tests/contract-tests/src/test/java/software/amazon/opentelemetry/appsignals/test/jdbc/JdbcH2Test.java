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

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.opentelemetry.appsignals.test.jdbc.operationtests.DBOperation;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JdbcH2Test extends JdbcContractTestBase {

  private static final String DB_SYSTEM = "h2";
  private static final String DB_CONNECTION_STRING = String.format("%s:mem:", DB_SYSTEM);
  private static final String DB_URL = String.format("jdbc:%s%s", DB_CONNECTION_STRING, DB_NAME);
  private static final String DB_DRIVER = "org.h2.Driver";
  private static final String DB_PLATFORM = "org.hibernate.dialect.H2Dialect";

  @Test
  public void testSuccess() {
    assertSuccess(
        DB_SYSTEM, DBOperation.SELECT, DB_USER, DB_NAME, DB_CONNECTION_STRING, null, null);
  }

  @Test
  public void testFault() {
    assertFault(DB_SYSTEM, DBOperation.SELECT, DB_USER, DB_NAME, DB_CONNECTION_STRING, null, null);
  }

  @Override
  protected Map<String, String> getApplicationExtraEnvironmentVariables() {
    return Map.of(
        "DB_URL", DB_URL,
        "DB_DRIVER", DB_DRIVER,
        "DB_USERNAME", DB_USER,
        "DB_PASSWORD", DB_PASSWORD,
        "DB_PLATFORM", DB_PLATFORM);
  }
}
