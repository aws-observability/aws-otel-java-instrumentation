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

import static io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startable;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(Lifecycle.PER_CLASS)
public class JdbcMySQLTest extends JdbcContractTestBase {

  private static final String NETWORK_ALIAS = "mysql";
  private static final String DB_SYSTEM = "mysql";
  private static final String DB_CONNECTION_STRING =
      String.format("%s://%s:%s", DB_SYSTEM, NETWORK_ALIAS, MySQLContainer.MYSQL_PORT);
  private static final String DB_URL = String.format("jdbc:%s/%s", DB_CONNECTION_STRING, DB_NAME);
  private static final String DB_DRIVER = "com.mysql.cj.jdbc.Driver";
  private static final String DB_PLATFORM = "org.hibernate.dialect.MySQL8Dialect";
  private static final String MYSQL_IDENTIFIER =
      String.format("%s|%s|%s", DB_NAME, NETWORK_ALIAS, MySQLContainer.MYSQL_PORT);

  private MySQLContainer<?> mySQLContainer;

  @AfterEach
  public void afterEach() {
    mySQLContainer.stop();
  }

  //  @Test
  //  public void testSuccessCreateDatabase() {
  //    assertSuccess(
  //        DB_SYSTEM,
  //        DB_CREATE_DATABASE_OPERATION,
  //        DB_USER,
  //        DB_NAME,
  //        DB_CONNECTION_STRING,
  //        DB_RESOURCE_TYPE,
  //        MYSQL_IDENTIFIER);
  //  }

  //  @Test
  //  public void testSuccessSelect() {
  //    assertSuccess(
  //        DB_SYSTEM,
  //        DB_SELECT_OPERATION,
  //        DB_USER,
  //        DB_NAME,
  //        DB_CONNECTION_STRING,
  //        DB_RESOURCE_TYPE,
  //        MYSQL_IDENTIFIER);
  //    assertExtraSelectSpan();
  //  }
  //
  //  @Test
  //  public void testFaultSelect() {
  //    assertFault(
  //        DB_SYSTEM,
  //        DB_SELECT_OPERATION,
  //        DB_USER,
  //        DB_NAME,
  //        DB_CONNECTION_STRING,
  //        DB_RESOURCE_TYPE,
  //        MYSQL_IDENTIFIER);
  //  }

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
    mySQLContainer =
        new MySQLContainer<>("mysql:8.4")
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withUsername(DB_USER)
            .withPassword(DB_PASSWORD)
            .withDatabaseName(DB_NAME)
            .withNetworkAliases(NETWORK_ALIAS)
            .withNetwork(network)
            .waitingFor(
                Wait.forLogMessage(".*database system is ready to accept connections.*", 1));
    return List.of(mySQLContainer);
  }

  private void assertExtraSelectSpan() {
    var resourceScopeSpans = mockCollectorClient.getTraces();
    assertThat(resourceScopeSpans)
        .satisfiesOnlyOnce(
            rss -> {
              assertThat(rss.getSpan().getKind()).isEqualTo(SPAN_KIND_CLIENT);
              var attributesList = rss.getSpan().getAttributesList();
              assertAwsAttributes(
                  attributesList,
                  "GET",
                  "success/" + DB_SELECT_OPERATION,
                  DB_SYSTEM,
                  DB_SELECT_OPERATION,
                  DB_USER,
                  null,
                  null);
            });
  }
}
