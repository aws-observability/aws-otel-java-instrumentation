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

plugins {
  java
  `java-library`
  id("com.gradleup.shadow")
}

dependencies {
  api("io.opentelemetry.javaagent:opentelemetry-testing-common")
  api("com.amazonaws:aws-java-sdk-core:1.11.0")

  // AWS SDK dependencies for version 1.11
  compileOnly("com.amazonaws:aws-java-sdk-sns:1.11.106")
  compileOnly("com.amazonaws:aws-java-sdk-secretsmanager:1.11.309")
  compileOnly("com.amazonaws:aws-java-sdk-stepfunctions:1.11.230")
  compileOnly("com.amazonaws:aws-java-sdk-lambda:1.11.678")
  compileOnly("com.amazonaws:aws-java-sdk-bedrock:1.12.744")
  compileOnly("com.amazonaws:aws-java-sdk-bedrockagent:1.12.744")
  compileOnly("com.amazonaws:aws-java-sdk-bedrockagentruntime:1.12.744")
  compileOnly("com.amazonaws:aws-java-sdk-bedrockruntime:1.12.744")

  implementation("com.google.guava:guava")
  implementation("io.opentelemetry:opentelemetry-api")
}
