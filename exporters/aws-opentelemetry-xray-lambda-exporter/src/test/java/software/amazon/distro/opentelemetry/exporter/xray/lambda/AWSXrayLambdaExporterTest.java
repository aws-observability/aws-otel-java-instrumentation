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

package software.amazon.distro.opentelemetry.exporter.xray.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class AwsXrayLambdaExporterTest {

  @Test
  public void testUdpExporterWithDefaults() {
    AwsXrayLambdaExporter exporter = new AwsXrayLambdaExporterBuilder().build();
    UdpSender sender = exporter.getSender();
    assertThat(sender.getEndpoint().getHostName())
        .isEqualTo("localhost"); // getHostName implicitly converts 127.0.0.1 to localhost
    assertThat(sender.getEndpoint().getPort()).isEqualTo(2000);
    assertThat(exporter.getPayloadPrefix()).endsWith("T1S");
  }

  @Test
  public void testUdpExporterWithCustomEndpointAndSample() {
    AwsXrayLambdaExporter exporter =
        new AwsXrayLambdaExporterBuilder()
            .setEndpoint("somehost:1000")
            .setPayloadSampleDecision(TracePayloadSampleDecision.UNSAMPLED)
            .build();
    UdpSender sender = exporter.getSender();
    assertThat(sender.getEndpoint().getHostName()).isEqualTo("somehost");
    assertThat(sender.getEndpoint().getPort()).isEqualTo(1000);
    assertThat(exporter.getPayloadPrefix()).endsWith("T1U");
  }

  @Test
  public void testUdpExporterWithInvalidEndpoint() {
    assertThatThrownBy(
            () -> {
              new AwsXrayLambdaExporterBuilder().setEndpoint("invalidhost");
            })
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid endpoint, must be a valid URL: invalidhost");
  }

  @Test
  public void shouldUseExpectedEnvironmentVariablesToConfigureEndpoint() {
    // Create a test environment map
    Map<String, String> testEnv = new HashMap<>();
    testEnv.put("AWS_LAMBDA_FUNCTION_NAME", "testFunctionName");
    testEnv.put("AWS_XRAY_DAEMON_ADDRESS", "someaddress:1234");

    // Create builder with test environment
    AwsXrayLambdaExporterBuilder builder =
        new AwsXrayLambdaExporterBuilder().withEnvironmentVariables(testEnv);

    // Verify that environment variables are set correctly
    assertThat(builder.getEnvironmentVariables())
        .containsEntry("AWS_LAMBDA_FUNCTION_NAME", "testFunctionName")
        .containsEntry("AWS_XRAY_DAEMON_ADDRESS", "someaddress:1234");

    // Build the exporter and verify the configuration
    AwsXrayLambdaExporter exporter = builder.build();
    UdpSender sender = exporter.getSender();

    assertThat(sender.getEndpoint().getHostName()).isEqualTo("someaddress");
    assertThat(sender.getEndpoint().getPort()).isEqualTo(1234);
  }

  @Test
  public void testExportDefaultBehavior() {
    UdpSender senderMock = mock(UdpSender.class);

    // mock SpanData
    SpanData spanData = buildSpanDataMock();

    AwsXrayLambdaExporter exporter = new AwsXrayLambdaExporterBuilder().setSender(senderMock).build();
    exporter.export(Collections.singletonList(spanData));

    // assert that the senderMock.send is called once
    verify(senderMock, times(1)).send(any(byte[].class));
    verify(senderMock)
        .send(
            argThat(
                (byte[] bytes) -> {
                  assertThat(bytes.length).isGreaterThan(0);
                  String payload = new String(bytes, StandardCharsets.UTF_8);
                  assertThat(payload)
                      .startsWith("{\"format\": \"json\", \"version\": 1}" + "\n" + "T1S");
                  return true;
                }));
  }

  @Test
  public void testExportWithSampledFalse() {
    UdpSender senderMock = mock(UdpSender.class);

    // mock SpanData
    SpanData spanData = buildSpanDataMock();

    AwsXrayLambdaExporter exporter =
        new AwsXrayLambdaExporterBuilder()
            .setSender(senderMock)
            .setPayloadSampleDecision(TracePayloadSampleDecision.UNSAMPLED)
            .build();
    exporter.export(Collections.singletonList(spanData));

    verify(senderMock, times(1)).send(any(byte[].class));
    verify(senderMock)
        .send(
            argThat(
                (byte[] bytes) -> {
                  assertThat(bytes.length).isGreaterThan(0);
                  String payload = new String(bytes, StandardCharsets.UTF_8);
                  assertThat(payload)
                      .startsWith("{\"format\": \"json\", \"version\": 1}" + "\n" + "T1U");
                  return true;
                }));
  }

  private SpanData buildSpanDataMock() {
    SpanData mockSpanData = mock(SpanData.class);

    Attributes spanAttributes =
        Attributes.of(AttributeKey.stringKey("original key"), "original value");
    when(mockSpanData.getAttributes()).thenReturn(spanAttributes);
    when(mockSpanData.getTotalAttributeCount()).thenReturn(spanAttributes.size());
    when(mockSpanData.getKind()).thenReturn(SpanKind.SERVER);

    SpanContext parentSpanContextMock = mock(SpanContext.class);
    when(mockSpanData.getParentSpanContext()).thenReturn(parentSpanContextMock);

    SpanContext spanContextMock = mock(SpanContext.class);
    TraceFlags spanContextTraceFlagsMock = mock(TraceFlags.class);
    when(spanContextMock.isValid()).thenReturn(true);
    when(spanContextMock.getTraceFlags()).thenReturn(spanContextTraceFlagsMock);
    when(mockSpanData.getSpanContext()).thenReturn(spanContextMock);

    TraceState traceState = TraceState.builder().build();
    when(spanContextMock.getTraceState()).thenReturn(traceState);

    when(mockSpanData.getStatus()).thenReturn(StatusData.unset());
    when(mockSpanData.getInstrumentationScopeInfo())
        .thenReturn(InstrumentationScopeInfo.create("Dummy Scope"));

    Resource testResource = Resource.empty();
    when(mockSpanData.getResource()).thenReturn(testResource);

    return mockSpanData;
  }
}

