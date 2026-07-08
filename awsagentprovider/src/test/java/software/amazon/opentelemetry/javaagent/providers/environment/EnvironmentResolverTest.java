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

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.resources.Resource;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * The SDK-side resolver must produce the SAME aws.local.environment the CloudWatch agent's
 * awsapplicationsignals resolver produces, from the same OTel resource attributes. Precedence:
 * explicit deployment.environment[.name] -> eks/k8s cluster/namespace -> ecs:&lt;cluster&gt; ->
 * ec2:&lt;asg&gt; -> ec2:default.
 */
class EnvironmentResolverTest {

  private static final Supplier<String> NO_ASG = () -> "";

  private static Resource resourceOf(String... keyValues) {
    AttributesBuilder builder = Attributes.builder();
    for (int i = 0; i < keyValues.length; i += 2) {
      builder.put(keyValues[i], keyValues[i + 1]);
    }
    return Resource.create(builder.build());
  }

  private static String resolve(Resource resource, Supplier<String> asg) {
    return EnvironmentResolver.resolveLocalEnvironment(resource, asg);
  }

  @Test
  void explicitEnvironmentNameWins() {
    Resource resource =
        resourceOf(
            "deployment.environment.name", "my-env",
            "k8s.cluster.name", "c",
            "k8s.namespace.name", "ns",
            "cloud.platform", "aws_eks",
            "ec2.tag.aws:autoscaling:groupName", "asg");
    assertThat(resolve(resource, () -> "asg")).isEqualTo("my-env");
  }

  @Test
  void legacyDeploymentEnvironmentHonored() {
    assertThat(resolve(resourceOf("deployment.environment", "legacy-env"), NO_ASG))
        .isEqualTo("legacy-env");
  }

  @Test
  void environmentNamePreferredOverLegacy() {
    Resource resource =
        resourceOf("deployment.environment.name", "new", "deployment.environment", "legacy");
    assertThat(resolve(resource, NO_ASG)).isEqualTo("new");
  }

  @Test
  void eksClusterNamespace() {
    Resource resource =
        resourceOf(
            "cloud.platform", "aws_eks",
            "k8s.cluster.name", "my-cluster",
            "k8s.namespace.name", "default");
    assertThat(resolve(resource, NO_ASG)).isEqualTo("eks:my-cluster/default");
  }

  @Test
  void eksMissingNamespaceFallsBackToUnknown() {
    Resource resource = resourceOf("cloud.platform", "aws_eks", "k8s.cluster.name", "my-cluster");
    assertThat(resolve(resource, NO_ASG)).isEqualTo("eks:my-cluster/UnknownNamespace");
  }

  @Test
  void nonEksKubernetesUsesK8sPrefix() {
    Resource resource = resourceOf("k8s.cluster.name", "c", "k8s.namespace.name", "team-a");
    assertThat(resolve(resource, NO_ASG)).isEqualTo("k8s:c/team-a");
  }

  @Test
  void ecsClusterFromArn() {
    Resource resource =
        resourceOf(
            "cloud.platform", "aws_ecs",
            "aws.ecs.cluster.arn", "arn:aws:ecs:us-west-2:123456789012:cluster/my-ecs-cluster");
    assertThat(resolve(resource, NO_ASG)).isEqualTo("ecs:my-ecs-cluster");
  }

  @Test
  void explicitEnvironmentWinsOverEcs() {
    Resource resource =
        resourceOf(
            "deployment.environment.name", "prod",
            "cloud.platform", "aws_ecs",
            "aws.ecs.cluster.arn", "arn:aws:ecs:us-west-2:123456789012:cluster/my-ecs-cluster");
    assertThat(resolve(resource, NO_ASG)).isEqualTo("prod");
  }

  @Test
  void ec2WithAsg() {
    Resource resource = resourceOf("cloud.platform", "aws_ec2");
    assertThat(resolve(resource, () -> "my-asg")).isEqualTo("ec2:my-asg");
  }

  @Test
  void ec2WithoutAsgDefaults() {
    Resource resource = resourceOf("cloud.platform", "aws_ec2");
    assertThat(resolve(resource, NO_ASG)).isEqualTo("ec2:default");
  }

  @Test
  void emptyResourceNonAwsReturnsGenericDefault() {
    // No platform signal (non-AWS / undetected host): the agent runs its "generic" resolver and
    // emits "generic:default", so the SDK matches that rather than claiming ec2:default.
    assertThat(resolve(Resource.empty(), NO_ASG)).isEqualTo("generic:default");
  }

  @Test
  void nullResourceReturnsGenericDefault() {
    assertThat(resolve(null, NO_ASG)).isEqualTo("generic:default");
  }

  @Test
  void ec2DefaultWhenHostIdPresent() {
    // host.id (EC2 instance id from the OTel EC2 detector) marks the host as EC2.
    assertThat(resolve(resourceOf("cloud.platform", "aws_ec2", "host.id", "i-0abc"), NO_ASG))
        .isEqualTo("ec2:default");
  }

  @Test
  void nonAwsHostWithServiceNameReturnsGenericDefault() {
    assertThat(resolve(resourceOf("service.name", "svc", "host.name", "my-vm"), NO_ASG))
        .isEqualTo("generic:default");
  }

  @Test
  void kubernetesPrecedesEc2() {
    // When both k8s and ec2 ASG are present (unusual), Kubernetes wins, matching the agent.
    Resource resource = resourceOf("k8s.cluster.name", "c", "k8s.namespace.name", "ns");
    assertThat(resolve(resource, () -> "asg")).isEqualTo("k8s:c/ns");
  }

  @Test
  void asgSupplierNotInvokedWhenNotEc2Branch() {
    // EKS resolves without ever calling the ASG supplier (no IMDS lookup off the EC2 path).
    Resource resource =
        resourceOf("cloud.platform", "aws_eks", "k8s.cluster.name", "c", "k8s.namespace.name", "n");
    Supplier<String> throwingAsg =
        () -> {
          throw new AssertionError("ASG supplier must not be invoked on the EKS branch");
        };
    assertThat(resolve(resource, throwingAsg)).isEqualTo("eks:c/n");
  }

  @Test
  void whitespaceOnlyExplicitEnvIgnored() {
    Resource resource =
        resourceOf(
            "deployment.environment.name", "   ",
            "cloud.platform", "aws_eks",
            "k8s.cluster.name", "my-cluster",
            "k8s.namespace.name", "default");
    assertThat(resolve(resource, NO_ASG)).isEqualTo("eks:my-cluster/default");
  }

  @Test
  void ecsEmptyClusterArnFallsThroughToGenericDefault() {
    // Empty cluster segment + cloud.platform=aws_ecs (not aws_ec2) and no EC2 signal →
    // "generic:default" (the agent's generic resolver), not ec2:default.
    Resource resource =
        resourceOf("cloud.platform", "aws_ecs", "aws.ecs.cluster.arn", "arn:.../cluster/");
    assertThat(resolve(resource, NO_ASG)).isEqualTo("generic:default");
  }

  @Test
  void withLocalEnvironmentStampsResolvedValue() {
    Resource resource =
        resourceOf(
            "cloud.platform", "aws_eks",
            "k8s.cluster.name", "my-cluster",
            "k8s.namespace.name", "default");
    Resource stamped = EnvironmentResolver.withLocalEnvironment(resource, NO_ASG);
    assertThat(stamped.getAttribute(EnvironmentResolver.LOCAL_ENVIRONMENT_KEY))
        .isEqualTo("eks:my-cluster/default");
  }

  @Test
  void withLocalEnvironmentDoesNotOverwriteExisting() {
    Resource resource =
        Resource.create(
            Attributes.builder()
                .put("aws.local.environment", "already-set")
                .put("cloud.platform", "aws_eks")
                .put("k8s.cluster.name", "c")
                .put("k8s.namespace.name", "ns")
                .build());
    Resource stamped = EnvironmentResolver.withLocalEnvironment(resource, NO_ASG);
    assertThat(stamped.getAttribute(EnvironmentResolver.LOCAL_ENVIRONMENT_KEY))
        .isEqualTo("already-set");
  }

  @Test
  void withLocalEnvironmentStampsGenericDefaultOnNonAwsHost() {
    // Non-AWS host → resolver returns "generic:default" (matches the agent's generic resolver),
    // so the key is stamped with that rather than omitted or set to ec2:default.
    Resource resource = resourceOf("service.name", "svc", "host.name", "my-vm");
    Resource stamped = EnvironmentResolver.withLocalEnvironment(resource, NO_ASG);
    assertThat(stamped.getAttribute(EnvironmentResolver.LOCAL_ENVIRONMENT_KEY))
        .isEqualTo("generic:default");
  }

  @Test
  void hasPlatformContextTrueForKnownPlatform() {
    assertThat(EnvironmentResolver.hasPlatformContext(resourceOf("cloud.platform", "aws_ec2")))
        .isTrue();
    assertThat(EnvironmentResolver.hasPlatformContext(Resource.empty())).isFalse();
    assertThat(EnvironmentResolver.hasPlatformContext(null)).isFalse();
  }
}
