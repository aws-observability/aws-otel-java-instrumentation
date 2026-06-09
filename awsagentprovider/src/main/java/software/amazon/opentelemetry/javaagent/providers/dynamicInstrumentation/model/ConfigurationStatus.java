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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model;

/**
 * Status of an instrumentation configuration.
 *
 * <p>Matches Python StatusReporter enum values exactly.
 */
public enum ConfigurationStatus {
  /** Configuration applied and ready, never hit yet */
  READY,

  /** Configuration hit in the last reporting period */
  ACTIVE,

  /** Configuration disabled (maxHits reached or expired) */
  DISABLED,

  /** Configuration failed to apply */
  ERROR
}
