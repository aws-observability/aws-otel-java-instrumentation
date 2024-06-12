package software.amazon.opentelemetry.appsignals.test.jdbc.operationtests;

public class JdbcOperationTesterProvider {

    private JdbcOperationTesterProvider() {

    }

    public static JdbcOperationTester getOperationTester(DBOperation operation, String dbSystem, String dbUser, String jdbcUrl, String dbName, String dbTable) {
        switch (operation) {
        case CREATE_DATABASE:
            return  new JdbcCreateDatabaseOperationTester(dbSystem, dbUser, jdbcUrl, dbName, dbTable);
        case SELECT:
            return new JdbcSelectOperationTester(dbSystem, dbUser, jdbcUrl, dbName, dbTable);
        default:
            throw new UnsupportedOperationException("No tests for operation: " + operation);
        }
    }
}
