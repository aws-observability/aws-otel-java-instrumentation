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

import static org.assertj.core.api.Assertions.assertThat;
import static software.amazon.opentelemetry.appsignals.test.jdbc.operationtests.DBOperation.CREATE_DATABASE;

import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

public class JdbcCreateDatabaseOperationTester extends JdbcOperationTester {

  public JdbcCreateDatabaseOperationTester(
      String dbSystem, String dbUser, String jdbcUrl, String dbName, String dbTable) {
    super(dbSystem, dbUser, jdbcUrl, dbName, CREATE_DATABASE.toString(), dbTable);
  }

  @Override
  protected void assertOperationSemanticConventions(ResourceScopeSpan resourceScopeSpan) {
    assertThat(resourceScopeSpan.getSpan().getName()).isEqualTo(String.format("%s", this.dbName));

    var attributesList = resourceScopeSpan.getSpan().getAttributesList();
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_STATEMENT);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(
                      String.format(
                          "%s %s",
                          CREATE_DATABASE.toString().toLowerCase(), CREATE_DATABASE.getTargetDB()));
            });
  }
}
