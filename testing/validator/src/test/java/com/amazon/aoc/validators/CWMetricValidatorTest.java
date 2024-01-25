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

package com.amazon.aoc.validators;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.assertj.core.util.Lists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

import com.amazon.aoc.callers.HttpCaller;
import com.amazon.aoc.exception.BaseException;
import com.amazon.aoc.fileconfigs.LocalPathExpectedTemplate;
import com.amazon.aoc.helpers.CWMetricHelper;
import com.amazon.aoc.models.Context;
import com.amazon.aoc.models.SampleAppResponse;
import com.amazon.aoc.models.ValidationConfig;
import com.amazon.aoc.services.CloudWatchService;
import com.amazonaws.services.cloudwatch.model.Metric;

import kotlin.Pair;


/**
 * This class covers the tests for CWMetricValidator. Tests are not run for Windows, due to file
 * path differences.
 */
@DisabledIf("isWindows")
public class CWMetricValidatorTest {
  private CWMetricHelper cwMetricHelper = new CWMetricHelper();
  private static final String SERVICE_DIMENSION = "Service";
  private static final String REMOTE_SERVICE_DIMENSION= "RemoteService";
  private static final String REMOTE_TARGET_DIMENSION = "RemoteTarget";
  private static final String TEMPLATE_ROOT =
      "file://" + System.getProperty("user.dir") + "/src/test/test-resources/";
  private static final String SERVICE_NAME = "serviceName";
  private static final String REMOTE_SERVICE_NAME = "remoteServiceName";
  private static final String REMOTE_TARGET_NAME = "remoteTargetName";
  private static final String REMOTE_SERVICE_DEPLOYMENT_NAME = "remoteServiceDeploymentName";

  private Context context;
  private HttpCaller httpCaller;

  static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().startsWith("win");
  }

  @BeforeEach
  public void setUp() throws Exception {
    context = initContext();
    httpCaller = mockHttpCaller("traceId");
  }

  /**
   * test validation with local file path template file.
   *
   * @throws Exception when test fails
   */
  @Test
  public void testValidationSucceedWithCustomizedFilePath() throws Exception {
    ValidationConfig validationConfig =
        initValidationConfig(TEMPLATE_ROOT + "endToEnd_expectedMetrics.mustache");
    runBasicValidation(validationConfig);
  }

  /**
   * test validation with enum template.
   *
   * @throws Exception when test fails
   */
  @Test
  public void testValidationSucceed() throws Exception {
    ValidationConfig validationConfig = initValidationConfig("EKS_OUTGOING_HTTP_CALL_METRIC");
    runBasicValidation(validationConfig);
  }

  @Test
  public void testValidateEndToEnd_Success() throws Exception {
    ValidationConfig validationConfig =
        initValidationConfig(TEMPLATE_ROOT + "endToEnd_expectedMetrics.mustache ");

    List<Metric> localServiceMetrics = getTestMetrics("endToEnd_localMetricsWithService");
    List<Metric> remoteServiceMetrics = getTestMetrics("endToEnd_remoteMetricsWithService");
    List<Metric> remoteMetricsWithRemoteApp = getTestMetrics("endToEnd_remoteMetricsWithRemoteApp");
    List<Metric> remoteMetricsWithAmazon = getTestMetrics("endToEnd_remoteMetricsWithAmazon");
    List<Metric> remoteMetricsWithAwsSdk = getTestMetrics("endToEnd_remoteMetricsWithAwsSdk");
    List<Metric> remoteMetricsWithS3Target = getTestMetrics("endToEnd_remoteMetricsWithS3Target");

    CloudWatchService cloudWatchService =
        mockCloudWatchService(
            localServiceMetrics,
            remoteServiceMetrics,
            remoteMetricsWithRemoteApp,
            remoteMetricsWithAmazon,
            remoteMetricsWithAwsSdk,
            remoteMetricsWithS3Target);

    validate(validationConfig, cloudWatchService);
  }

  @Test
  public void testValidateEndToEnd_MissingRemoteService() throws Exception {
    ValidationConfig validationConfig =
        initValidationConfig(TEMPLATE_ROOT + "endToEnd_expectedMetrics.mustache");

    List<Metric> localServiceMetrics = getTestMetrics("endToEnd_localMetricsWithService");
    List<Metric> remoteServiceMetrics = getTestMetrics("endToEnd_remoteMetricsWithService");
    // Skip remoteMetricsWithRemoteApp, which contains the [RemoteService] rollup.
    List<Metric> remoteMetricsWithRemoteApp = Lists.newArrayList();
    List<Metric> remoteMetricsWithAmazon = getTestMetrics("endToEnd_remoteMetricsWithAmazon");
    List<Metric> remoteMetricsWithAwsSdk = getTestMetrics("endToEnd_remoteMetricsWithAwsSdk");
    List<Metric> remoteMetricsWithAwsSdkWithTarget = getTestMetrics("endToEnd_remoteMetricsWithAwsSdk");

    CloudWatchService cloudWatchService =
        mockCloudWatchService(
            localServiceMetrics,
            remoteServiceMetrics,
            remoteMetricsWithRemoteApp,
            remoteMetricsWithAmazon,
            remoteMetricsWithAwsSdk,
            remoteMetricsWithAwsSdkWithTarget);

    try {
      validate(validationConfig, cloudWatchService);
    } catch (BaseException be) {
      String actualMessage = be.getMessage();
      String expectedMessage =
          "toBeCheckedMetricList: {Namespace: metricNamespace,MetricName: metricName,Dimensions: [{Name: RemoteService,Value: "
              + REMOTE_SERVICE_DEPLOYMENT_NAME
              + "}]} is not found in";
      assertTrue(actualMessage.contains(expectedMessage), actualMessage);
    }
  }

  private Context initContext() {
    // fake vars
    String testingId = "testingId";
    String region = "region";
    String namespace = "metricNamespace";

    // faked context
    Context context = new Context(testingId, region, false, false);
    context.setMetricNamespace(namespace);
    context.setServiceName(SERVICE_NAME);
    context.setRemoteServiceName(REMOTE_SERVICE_NAME);
    context.setRemoteServiceDeploymentName(REMOTE_SERVICE_DEPLOYMENT_NAME);
    return context;
  }

  private HttpCaller mockHttpCaller(String traceId) throws Exception {
    HttpCaller httpCaller = mock(HttpCaller.class);
    SampleAppResponse sampleAppResponse = new SampleAppResponse();
    sampleAppResponse.setTraceId(traceId);
    when(httpCaller.callSampleApp()).thenReturn(sampleAppResponse);
    return httpCaller;
  }

  private List<Metric> getTestMetrics(String fileName) throws Exception {
    String localServiceTemplate = TEMPLATE_ROOT + fileName + ".mustache";
    return cwMetricHelper.listExpectedMetrics(
        context, new LocalPathExpectedTemplate(localServiceTemplate), httpCaller);
  }

  private CloudWatchService mockCloudWatchService(
      List<Metric> localServiceMetrics,
      List<Metric> remoteServiceMetrics,
      List<Metric> remoteMetricsWithRemoteApp,
      List<Metric> remoteMetricsWithAmazon,
      List<Metric> remoteMetricsWithAwsSdk,
      List<Metric> remoteMetricsWithS3Target) {
    CloudWatchService cloudWatchService = mock(CloudWatchService.class);
    Lists.newArrayList();
    when(cloudWatchService.listMetrics(any(), any(), eq(Arrays.asList(new Pair<String,String>(SERVICE_DIMENSION, SERVICE_NAME)))))
        .thenReturn(localServiceMetrics);
    when(cloudWatchService.listMetrics(
            any(), any(), eq(Arrays.asList(new Pair<String,String>(SERVICE_DIMENSION, REMOTE_SERVICE_NAME)))))
        .thenReturn(remoteServiceMetrics);
    when(cloudWatchService.listMetrics(
            any(), any(), eq(Arrays.asList(new Pair<String,String>(REMOTE_SERVICE_DIMENSION, REMOTE_SERVICE_DEPLOYMENT_NAME)))))
        .thenReturn(remoteMetricsWithRemoteApp);
    when(cloudWatchService.listMetrics(
            any(), any(), eq(Arrays.asList(new Pair<String,String>(REMOTE_SERVICE_DIMENSION, "www.amazon.com")))))
        .thenReturn(remoteMetricsWithAmazon);
    when(cloudWatchService.listMetrics(
      any(), any(), eq(Arrays.asList(new Pair<String,String>(REMOTE_SERVICE_DIMENSION, "AWS.SDK.S3")))))
        .thenReturn(remoteMetricsWithAwsSdk);
        when(cloudWatchService.listMetrics(
      any(), any(), eq(Arrays.asList(
          new Pair<String,String>(REMOTE_SERVICE_DIMENSION, "AWS.SDK.S3"),
          new Pair<String,String>(REMOTE_TARGET_DIMENSION, "e2e-test-bucket-name")
        ))))
        .thenReturn(remoteMetricsWithS3Target);
    return cloudWatchService;
  }

  private ValidationConfig initValidationConfig(String metricTemplate) {
    ValidationConfig validationConfig = new ValidationConfig();
    validationConfig.setCallingType("http");
    validationConfig.setExpectedMetricTemplate(metricTemplate);
    return validationConfig;
  }

  private void runBasicValidation(ValidationConfig validationConfig) throws Exception {
    // fake vars
    String traceId = "fakedtraceid";

    // fake and mock a cloudwatch service
    List<Metric> metrics =
        cwMetricHelper.listExpectedMetrics(
            context, validationConfig.getExpectedMetricTemplate(), httpCaller);
    CloudWatchService cloudWatchService = mock(CloudWatchService.class);

    // mock listMetrics
    when(cloudWatchService.listMetrics(any(), any(), any())).thenReturn(metrics);

    // start validation
    validate(validationConfig, cloudWatchService);
  }

  private void validate(ValidationConfig validationConfig, CloudWatchService cloudWatchService)
      throws Exception {
    CWMetricValidator validator = new CWMetricValidator();
    validator.init(
        context, validationConfig, httpCaller, validationConfig.getExpectedMetricTemplate());
    validator.setCloudWatchService(cloudWatchService);
    validator.setMaxRetryCount(1);
    validator.validate();
  }
}
