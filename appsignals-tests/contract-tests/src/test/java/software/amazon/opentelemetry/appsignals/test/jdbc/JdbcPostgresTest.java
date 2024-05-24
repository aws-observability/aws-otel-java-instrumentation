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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startable;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class JdbcPostgresTest extends JdbcContractTestBase {

  private static final String NETWORK_ALIAS = "postgres";
  private static final String DB_SYSTEM = "postgresql";
  private static final String DB_CONNECTION_STRING =
      String.format("%s://%s:%s", DB_SYSTEM, NETWORK_ALIAS, PostgreSQLContainer.POSTGRESQL_PORT);
  private static final String DB_URL = String.format("jdbc:%s/%s", DB_CONNECTION_STRING, DB_NAME);
  private static final String DB_DRIVER = "org.postgresql.Driver";
  private static final String DB_PLATFORM = "org.hibernate.dialect.PostgreSQLDialect";
  private static final String POSTGRES_IDENTIFIER =
      String.format("%s|%s|%s", DB_NAME, NETWORK_ALIAS, PostgreSQLContainer.POSTGRESQL_PORT);

  private PostgreSQLContainer<?> postgreSqlContainer;

  @AfterEach
  public void afterEach() {
    // dependent containers are not stopped between tests, only the application container.
    postgreSqlContainer.stop();
  }

  @Test
  public void testSuccess() {
    assertSuccess(
        DB_SYSTEM,
        DB_OPERATION,
        DB_USER,
        DB_NAME,
        DB_CONNECTION_STRING,
        DB_RESOURCE_TYPE,
        POSTGRES_IDENTIFIER);
  }

  @Test
  public void testFault() {
    assertFault(
        DB_SYSTEM,
        DB_OPERATION,
        DB_USER,
        DB_NAME,
        DB_CONNECTION_STRING,
        DB_RESOURCE_TYPE,
        POSTGRES_IDENTIFIER);
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

  @Override
  protected List<Startable> getApplicationDependsOnContainers() {
    this.postgreSqlContainer =
        new PostgreSQLContainer<>("postgres:16.3")
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withUsername(DB_USER)
            .withPassword(DB_PASSWORD)
            .withDatabaseName(DB_NAME)
            .withNetworkAliases(NETWORK_ALIAS)
            .withNetwork(network)
            .waitingFor(
                Wait.forLogMessage(".*database system is ready to accept connections.*", 1));
    return List.of(postgreSqlContainer);
  }
}
