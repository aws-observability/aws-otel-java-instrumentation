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

package com.amazon.aoc;

import com.amazon.aoc.helpers.ConfigLoadHelper;
import com.amazon.aoc.models.*;
import com.amazon.aoc.services.CloudWatchService;
import com.amazon.aoc.validators.ValidatorFactory;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import lombok.extern.log4j.Log4j2;
import picocli.CommandLine;

@CommandLine.Command(name = "e2etest", mixinStandardHelpOptions = true, version = "1.0")
@Log4j2
public class App implements Callable<Integer> {

  @CommandLine.Option(names = {"-c", "--config-path"})
  private String configPath;

  @CommandLine.Option(
      names = {"-t", "--testing-id"},
      defaultValue = "dummy-id")
  private String testingId;

  @CommandLine.Option(names = {"--account-id"})
  private String accountId;

  @CommandLine.Option(names = {"--language"})
  private String language;

  @CommandLine.Option(
      names = {"--metric-namespace"},
      defaultValue = "AWSObservability/CloudWatchOTService")
  private String metricNamespace;

  @CommandLine.Option(
      names = {"--app-namespace"},
      defaultValue = "demo")
  private String appNamespace;

  @CommandLine.Option(
      names = {"--cluster"},
      defaultValue = "demo-cluster")
  private String cluster;

  @CommandLine.Option(
      names = {"--service-name"},
      defaultValue = "demo-deployment")
  private String serviceName;

  @CommandLine.Option(names = {"--remote-service-name"})
  private String remoteServiceName;

  @CommandLine.Option(names = {"--remote-service-deployment-name"})
  private String remoteServiceDeploymentName;

  @CommandLine.Option(names = {"--endpoint"})
  private String endpoint;

  @CommandLine.Option(names = {"--request-body"})
  private String requestBody;

  @CommandLine.Option(
      names = {"--log-group"},
      defaultValue = "/aws/appsignals/eks")
  private String logGroup;

  @CommandLine.Option(
      names = {"--region"},
      defaultValue = "us-west-2")
  private String region;

  @CommandLine.Option(names = {"--availability-zone"})
  private String availabilityZone;

  @CommandLine.Option(names = {"--ecs-context"})
  private String ecsContext;

  @CommandLine.Option(names = {"--ec2-context"})
  private String ec2Context;

  @CommandLine.Option(names = {"--cloudwatch-context"})
  private String cloudWatchContext;

  @CommandLine.Option(
      names = {"--alarm-names"},
      description = "the cloudwatch alarm names")
  private List<String> alarmNameList;

  @CommandLine.Option(
      names = {"--mocked-server-validating-url"},
      description = "mocked server validating url")
  private String mockedServerValidatingUrl;

  @CommandLine.Option(
      names = {"--canary"},
      defaultValue = "false")
  private boolean isCanary;

  @CommandLine.Option(
      names = {"--testcase"},
      defaultValue = "otlp_mock")
  private String testcase;

  @CommandLine.Option(
      names = {"--cortex-instance-endpoint"},
      description = "cortex instance validating url")
  private String cortexInstanceEndpoint;

  @CommandLine.Option(
      names = {"--rollup"},
      defaultValue = "true")
  private boolean isRollup;

  private static final String TEST_CASE_DIM_KEY = "testcase";
  private static final String CANARY_NAMESPACE = "Otel/Canary";
  private static final String CANARY_METRIC_NAME = "Success";

  public static void main(String[] args) throws Exception {
    int exitCode = new CommandLine(new App()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    final Instant startTime = Instant.now();
    // build context
    Context context = new Context(this.testingId, this.region, this.isCanary, this.isRollup);
    context.setAccountId(this.accountId);
    context.setAvailabilityZone(this.availabilityZone);
    context.setMetricNamespace(this.metricNamespace);
    context.setAppNamespace(this.appNamespace);
    context.setCluster(this.cluster);
    context.setServiceName(this.serviceName);
    context.setRemoteServiceName(this.remoteServiceName);
    context.setRemoteServiceDeploymentName(this.remoteServiceDeploymentName);
    context.setEndpoint(this.endpoint);
    context.setRequestBody(this.requestBody);
    context.setLogGroup(this.logGroup);
    context.setEcsContext(buildJsonContext(ecsContext, ECSContext.class));
    context.setEc2Context(buildJsonContext(ec2Context, EC2Context.class));
    context.setCloudWatchContext(buildJsonContext(cloudWatchContext, CloudWatchContext.class));
    context.setAlarmNameList(alarmNameList);
    context.setMockedServerValidatingUrl(mockedServerValidatingUrl);
    context.setCortexInstanceEndpoint(this.cortexInstanceEndpoint);
    context.setTestcase(testcase);
    context.setLanguage(language);

    log.info(context);

    // load config
    List<ValidationConfig> validationConfigList =
        new ConfigLoadHelper().loadConfigFromFile(configPath);

    // run validation
    validate(context, validationConfigList);

    Instant endTime = Instant.now();
    Duration duration = Duration.between(startTime, endTime);
    log.info("Validation has completed in {} minutes.", duration.toMinutes());
    return null;
  }

  private void validate(Context context, List<ValidationConfig> validationConfigList)
      throws Exception {
    CloudWatchService cloudWatchService = new CloudWatchService(region);
    Dimension dimension = new Dimension().withName(TEST_CASE_DIM_KEY).withValue(this.testcase);
    int maxValidationCycles = 1;
    ValidatorFactory validatorFactory = new ValidatorFactory(context);
    if (this.isCanary) {
      maxValidationCycles = 20;
    }
    for (int cycle = 0; cycle < maxValidationCycles; cycle++) {
      for (ValidationConfig validationConfigItem : validationConfigList) {
        try {
          validatorFactory.launchValidator(validationConfigItem).validate();
        } catch (Exception e) {
          if (this.isCanary) {
            // emit metric
            cloudWatchService.putMetricData(CANARY_NAMESPACE, CANARY_METRIC_NAME, 0.0, dimension);
          }
          throw e;
        }
      }
      if (maxValidationCycles - cycle - 1 > 0) {
        log.info(
            "Completed {} validation cycle for current canary test. "
                + "Still need to validate {} cycles. Sleep 1 minute then proceed.",
            cycle + 1,
            maxValidationCycles - cycle - 1);
        TimeUnit.MINUTES.sleep(1);
      }
    }
    if (this.isCanary) {
      // emit metric
      cloudWatchService.putMetricData(CANARY_NAMESPACE, CANARY_METRIC_NAME, 1.0, dimension);
    }
  }

  private ECSContext buildECSContext(Map<String, String> ecsContextMap) {
    if (ecsContextMap == null) {
      return null;
    }
    return new ObjectMapper().convertValue(ecsContextMap, ECSContext.class);
  }

  private <T> T buildJsonContext(String metricContext, Class<T> clazz)
      throws JsonProcessingException {
    if (metricContext == null || metricContext.isEmpty()) {
      return null;
    }
    return new ObjectMapper().readValue(metricContext, clazz);
  }
}
