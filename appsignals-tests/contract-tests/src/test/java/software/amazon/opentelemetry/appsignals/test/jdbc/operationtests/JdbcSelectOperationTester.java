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
import static software.amazon.opentelemetry.appsignals.test.jdbc.operationtests.DBOperation.SELECT;

import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

public class JdbcSelectOperationTester extends JdbcOperationTester {

  public JdbcSelectOperationTester(
      String dbSystem, String dbUser, String jdbcUrl, String dbName, String dbTable) {
    super(dbSystem, dbUser, jdbcUrl, dbName, SELECT.toString(), dbTable);
  }

  @Override
  protected void assertOperationSemanticConventions(ResourceScopeSpan resourceScopeSpan) {
    assertThat(resourceScopeSpan.getSpan().getName())
        .isEqualTo(String.format("%s %s.%s", SELECT, this.dbName, this.dbTable));

    var attributesList = resourceScopeSpan.getSpan().getAttributesList();
    assertThat(attributesList)
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_STATEMENT);
              assertThat(attribute.getValue().getStringValue())
                  .isEqualTo(
                      String.format(
                          "%s count(*) from %s", SELECT.toString().toLowerCase(), this.dbTable));
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_SQL_TABLE);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(this.dbTable);
            })
        .satisfiesOnlyOnce(
            attribute -> {
              assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_OPERATION);
              assertThat(attribute.getValue().getStringValue()).isEqualTo(SELECT.toString());
            });
  }
}
