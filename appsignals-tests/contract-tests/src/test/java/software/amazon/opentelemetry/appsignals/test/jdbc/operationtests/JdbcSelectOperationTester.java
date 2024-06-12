package software.amazon.opentelemetry.appsignals.test.jdbc.operationtests;

import static org.assertj.core.api.Assertions.assertThat;

import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

import static software.amazon.opentelemetry.appsignals.test.jdbc.operationtests.DBOperation.SELECT;

public class JdbcSelectOperationTester extends JdbcOperationTester {

    public JdbcSelectOperationTester(String dbSystem, String dbUser, String jdbcUrl, String dbName, String dbTable) {
        super(dbSystem, dbUser, jdbcUrl, dbName, SELECT.toString(), dbTable);
    }

    @Override
    protected void assertOperationSemanticConventions(ResourceScopeSpan resourceScopeSpan) {
        assertThat(resourceScopeSpan.getSpan().getName()).isEqualTo(
                String.format("%s %s.%s", SELECT, this.dbName, this.dbTable));

        var attributesList = resourceScopeSpan.getSpan().getAttributesList();
        assertThat(attributesList).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_STATEMENT);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(
                    String.format("%s count(*) from %s", SELECT.toString().toLowerCase(), this.dbTable));
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_SQL_TABLE);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(this.dbTable);
        }).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_OPERATION);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(SELECT.toString());
        });
    }
}
