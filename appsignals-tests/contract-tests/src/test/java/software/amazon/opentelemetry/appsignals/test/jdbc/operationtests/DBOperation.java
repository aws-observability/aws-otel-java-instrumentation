package software.amazon.opentelemetry.appsignals.test.jdbc.operationtests;

public enum DBOperation {
    CREATE_DATABASE("CREATE DATABASE", "testdb2"),
    SELECT("SELECT", "testdb")
    ;

    DBOperation(String value, String targetDB) {
        this.value = value;
        this.targetDB = targetDB;
    }

    private final String value;
    private final String targetDB;

    public String getTargetDB() {
        return targetDB;
    }

    @Override
    public String toString() {
        return value;
    }
}
