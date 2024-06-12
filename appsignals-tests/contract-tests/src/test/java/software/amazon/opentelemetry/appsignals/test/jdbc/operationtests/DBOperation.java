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

public enum DBOperation {
  CREATE_DATABASE("CREATE DATABASE", "testdb2"),
  SELECT("SELECT", "testdb");

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
