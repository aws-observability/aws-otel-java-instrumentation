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

package software.amazon.opentelemetry.javaagent.providers.environment;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.resources.Resource;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Resolves {@code aws.local.environment} from OTel Resource attributes, mirroring the CloudWatch
 * agent's awsapplicationsignals resolver precedence so the SDK can compute the same environment
 * value the agent would, with no dependency on the agent process:
 *
 * <ol>
 *   <li>Explicit {@code deployment.environment[.name]} &rarr; use as-is
 *   <li>EKS / K8s &rarr; {@code "eks:<cluster>/<namespace>"} or {@code "k8s:<cluster>/<namespace>"}
 *   <li>ECS &rarr; {@code "ecs:<cluster>"} (cluster name from {@code aws.ecs.cluster.arn})
 *   <li>EC2 (host is actually EC2) &rarr; {@code "ec2:<asg>"} when an Auto Scaling group is known,
 *       else {@code "ec2:default"}
 *   <li>Otherwise (non-AWS / undetected host) &rarr; {@code "generic:default"}, matching the agent,
 *       which runs its "generic" resolver (Mode == onPremise) and emits {@code "generic:default"}
 *       there -- it never leaves Environment empty
 * </ol>
 *
 * <p>Scope is the LOCAL environment only ({@code aws.local.environment}); remote-environment
 * correlation is out of scope (it depends on the agent's cluster-wide pod watcher).
 *
 * <p>The Auto Scaling group is supplied lazily via a {@link Supplier} that is invoked ONLY when the
 * resolver reaches the EC2 branch, so EKS/ECS/explicit-env workloads never trigger an IMDS lookup.
 */
public final class EnvironmentResolver {

  /** Resource attribute key holding the resolved local environment. */
  public static final AttributeKey<String> LOCAL_ENVIRONMENT_KEY =
      AttributeKey.stringKey("aws.local.environment");

  private static final AttributeKey<String> DEPLOYMENT_ENVIRONMENT_NAME =
      AttributeKey.stringKey("deployment.environment.name");
  private static final AttributeKey<String> DEPLOYMENT_ENVIRONMENT =
      AttributeKey.stringKey("deployment.environment");
  private static final AttributeKey<String> K8S_CLUSTER_NAME =
      AttributeKey.stringKey("k8s.cluster.name");
  private static final AttributeKey<String> K8S_NAMESPACE_NAME =
      AttributeKey.stringKey("k8s.namespace.name");
  private static final AttributeKey<String> CLOUD_PLATFORM =
      AttributeKey.stringKey("cloud.platform");
  private static final AttributeKey<String> AWS_ECS_CLUSTER_ARN =
      AttributeKey.stringKey("aws.ecs.cluster.arn");
  private static final AttributeKey<String> HOST_ID = AttributeKey.stringKey("host.id");

  private static final String AWS_EKS = "aws_eks";
  private static final String AWS_EC2 = "aws_ec2";
  private static final String UNKNOWN_NAMESPACE = "UnknownNamespace";

  // Memoizes the (at most one) IMDS Auto Scaling group lookup across the whole agent, so the EC2
  // branch never hits IMDS more than once regardless of how many callers resolve the environment.
  private static final AtomicReference<String> CACHED_ASG = new AtomicReference<>(null);

  private EnvironmentResolver() {}

  /**
   * Resolves {@code aws.local.environment} from the given resource, using a process-wide memoized
   * IMDS lookup for the EC2 Auto Scaling group (performed only if the resolver reaches the EC2
   * branch).
   */
  public static String resolveLocalEnvironment(Resource resource) {
    return resolveLocalEnvironment(resource, EnvironmentResolver::cachedAutoScalingGroup);
  }

  /** Stamps {@code aws.local.environment} using the process-wide memoized ASG lookup. */
  public static Resource withLocalEnvironment(Resource resource) {
    return withLocalEnvironment(resource, EnvironmentResolver::cachedAutoScalingGroup);
  }

  private static String cachedAutoScalingGroup() {
    String existing = CACHED_ASG.get();
    if (existing != null) {
      return existing;
    }
    String fetched = new Ec2AutoScalingGroupFetcher().fetch();
    CACHED_ASG.compareAndSet(null, fetched);
    return CACHED_ASG.get();
  }

  /**
   * Resolves {@code aws.local.environment} from the given resource attributes.
   *
   * <p>The EC2 branch is gated on the host actually being EC2, mirroring the CloudWatch agent
   * (whose environment branches only run for EC2 / Kubernetes). On a non-AWS / non-K8s host the
   * agent leaves the Environment empty, so this returns {@code ""} rather than falsely claiming
   * {@code ec2:default}.
   *
   * @param resource the OTel resource (its attributes drive resolution)
   * @param asgSupplier supplies the EC2 Auto Scaling group name; invoked only on the EC2 branch.
   *     May be {@code null}, in which case the EC2 branch falls back to {@code ec2:default}.
   * @return the resolved environment string, or {@code ""} on a non-AWS / undetected host.
   */
  public static String resolveLocalEnvironment(Resource resource, Supplier<String> asgSupplier) {
    if (resource == null) {
      // No resource at all -> no platform signal -> same non-AWS fallback as below.
      return "generic:default";
    }

    // 1. Explicit deployment.environment[.name] wins outright.
    String explicit =
        firstNonEmpty(
            get(resource, DEPLOYMENT_ENVIRONMENT_NAME), get(resource, DEPLOYMENT_ENVIRONMENT));
    if (!explicit.isEmpty()) {
      return explicit;
    }

    // 2. Kubernetes (EKS / K8s): "<prefix>:<cluster>/<namespace>".
    String k8sCluster = get(resource, K8S_CLUSTER_NAME);
    if (!k8sCluster.isEmpty()) {
      String namespace = get(resource, K8S_NAMESPACE_NAME);
      if (namespace.isEmpty()) {
        namespace = UNKNOWN_NAMESPACE;
      }
      String prefix = AWS_EKS.equals(get(resource, CLOUD_PLATFORM)) ? "eks" : "k8s";
      return prefix + ":" + k8sCluster + "/" + namespace;
    }

    // 3. ECS: "ecs:<cluster>" — cluster name is the last segment of the ECS cluster ARN.
    String ecsClusterArn = get(resource, AWS_ECS_CLUSTER_ARN);
    if (!ecsClusterArn.isEmpty()) {
      String ecsCluster = ecsClusterArn.substring(ecsClusterArn.lastIndexOf('/') + 1);
      if (!ecsCluster.isEmpty()) {
        return "ecs:" + ecsCluster;
      }
    }

    // 4. EC2: only when the host is actually EC2 (matches the agent's Platform == ModeEC2 gate).
    //    Signals: cloud.platform=aws_ec2, host.id (EC2 instance id from the OTel EC2 detector),
    //    or the ASG tag (an IMDS-only EC2 signal, fetched lazily on this branch only).
    String asg = "";
    if (asgSupplier != null) {
      asg = trim(asgSupplier.get());
    }
    boolean isEc2 =
        AWS_EC2.equals(get(resource, CLOUD_PLATFORM))
            || !get(resource, HOST_ID).isEmpty()
            || !asg.isEmpty();
    if (isEc2) {
      return asg.isEmpty() ? "ec2:default" : "ec2:" + asg;
    }

    // 5. Non-AWS / undetected host: the CloudWatch agent runs its "generic" resolver here and
    //    emits "generic:default" (never empty), so mirror that instead of returning empty.
    return "generic:default";
  }

  /**
   * Returns true if the resource carries enough platform context for {@code ec2:default} to be a
   * real answer (rather than the empty-resource fallback). Callers that cache the resolved value
   * use this to avoid caching {@code ec2:default} before resource detection has populated.
   */
  public static boolean hasPlatformContext(Resource resource) {
    if (resource == null) {
      return false;
    }
    return !get(resource, CLOUD_PLATFORM).isEmpty()
        || !get(resource, K8S_CLUSTER_NAME).isEmpty()
        || !get(resource, AWS_ECS_CLUSTER_ARN).isEmpty()
        || !get(resource, HOST_ID).isEmpty();
  }

  /**
   * Returns a Resource with {@code aws.local.environment} stamped, computed via {@link
   * #resolveLocalEnvironment}. Idempotent: if the attribute is already present it is left
   * untouched. The resolver always yields a non-empty value (a platform scope, an explicit env, or
   * the {@code "generic:default"} fallback), matching the CloudWatch agent, which always sets
   * Environment. The empty-string guards below are defensive only.
   */
  public static Resource withLocalEnvironment(Resource resource, Supplier<String> asgSupplier) {
    if (resource == null) {
      String env = resolveLocalEnvironment(null, asgSupplier);
      return env.isEmpty()
          ? Resource.empty()
          : Resource.create(Attributes.of(LOCAL_ENVIRONMENT_KEY, env));
    }
    if (!get(resource, LOCAL_ENVIRONMENT_KEY).isEmpty()) {
      return resource;
    }
    String env = resolveLocalEnvironment(resource, asgSupplier);
    if (env.isEmpty()) {
      return resource;
    }
    return resource.merge(Resource.create(Attributes.of(LOCAL_ENVIRONMENT_KEY, env)));
  }

  private static String get(Resource resource, AttributeKey<String> key) {
    return trim(resource.getAttribute(key));
  }

  private static String firstNonEmpty(String a, String b) {
    return !a.isEmpty() ? a : b;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
