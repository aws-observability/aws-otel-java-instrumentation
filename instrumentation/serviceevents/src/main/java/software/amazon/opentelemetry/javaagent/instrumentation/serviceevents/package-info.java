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

/**
 * ServiceEvents - Deep Observability for Java Applications.
 *
 * <p>This package provides automatic instrumentation for deep function-level observability in Java
 * applications. It captures:
 *
 * <ul>
 *   <li>Function-level invocation metrics (duration, call patterns, exceptions)
 *   <li>HTTP endpoint performance tracking
 *   <li>Automated incident snapshots with full execution context
 *   <li>CloudWatch EMF (Embedded Metric Format) compatible telemetry
 * </ul>
 *
 * <p><strong>Key Components</strong>
 *
 * <ul>
 *   <li>{@link
 *       software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.ServiceEventsInstrumentation}
 *       - Main entry point for instrumentation lifecycle
 *   <li>{@link
 *       software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config.ServiceEventsConfig}
 *       - Configuration management with environment variable parsing
 *   <li>{@code software.amazon.opentelemetry.serviceevents.ServiceEventsDataStore} -
 *       Bootstrap-loaded shared state for aggregation, sampling, and investigation data
 *   <li>{@link
 *       software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.FunctionCallCollector}
 *       - Periodic function metrics aggregation and export
 *   <li>{@link
 *       software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.collectors.EndpointCollector}
 *       - HTTP endpoint metrics aggregation
 * </ul>
 *
 * <p><strong>Configuration</strong>
 *
 * <p>All configuration is done via environment variables prefixed with {@code
 * OTEL_AWS_SERVICE_EVENTS_}. {@link
 * software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config.ServiceEventsConfig#fromEnv()}
 * is the authoritative reader — see its source for the full set of supported variables, defaults,
 * and parsing rules.
 *
 * <p><strong>Usage</strong>
 *
 * <p>The instrumentation is automatically activated when the Java agent is loaded. For manual
 * initialization:
 *
 * <pre>{@code
 * ServiceEventsConfig config = ServiceEventsConfig.fromEnv();
 * ServiceEventsInstrumentation instrumentation = new ServiceEventsInstrumentation(config);
 * instrumentation.initialize();
 *
 * // During shutdown:
 * instrumentation.shutdown();
 * }</pre>
 *
 * @see
 *     software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.ServiceEventsInstrumentation
 * @see
 *     software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.config.ServiceEventsConfig
 */
package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents;
