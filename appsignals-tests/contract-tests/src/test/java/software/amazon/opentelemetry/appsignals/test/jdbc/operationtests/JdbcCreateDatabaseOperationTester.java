package software.amazon.opentelemetry.appsignals.test.jdbc.operationtests;

import static org.assertj.core.api.Assertions.assertThat;

import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeSpan;
import software.amazon.opentelemetry.appsignals.test.utils.SemanticConventionsConstants;

import static software.amazon.opentelemetry.appsignals.test.jdbc.operationtests.DBOperation.CREATE_DATABASE;

public class JdbcCreateDatabaseOperationTester extends JdbcOperationTester {

    public JdbcCreateDatabaseOperationTester(String dbSystem, String dbUser, String jdbcUrl, String dbName, String dbTable) {
        super(dbSystem, dbUser, jdbcUrl, dbName, CREATE_DATABASE.toString(), dbTable);
    }

    @Override
    protected void assertOperationSemanticConventions(ResourceScopeSpan resourceScopeSpan) {
        assertThat(resourceScopeSpan.getSpan().getName()).isEqualTo(String.format("%s", this.dbName));

        var attributesList = resourceScopeSpan.getSpan().getAttributesList();
        assertThat(attributesList).satisfiesOnlyOnce(attribute -> {
            assertThat(attribute.getKey()).isEqualTo(SemanticConventionsConstants.DB_STATEMENT);
            assertThat(attribute.getValue().getStringValue()).isEqualTo(
                    String.format("%s %s", CREATE_DATABASE.toString().toLowerCase(), CREATE_DATABASE.getTargetDB()));
        });
    }
}
