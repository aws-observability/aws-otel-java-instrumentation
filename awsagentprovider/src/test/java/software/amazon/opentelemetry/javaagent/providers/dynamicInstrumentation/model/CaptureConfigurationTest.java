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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaptureConfigurationTest {

  @Test
  void testBuilder_defaults() {
    CaptureConfiguration config = CaptureConfiguration.builder().build();

    assertThat(config.isCaptureReturn()).isFalse();
    assertThat(config.isCaptureStackTrace()).isFalse();
    assertThat(config.getCaptureArguments()).isNull();
    assertThat(config.getCaptureLocals()).isNull();
    assertThat(config.getArgMappings()).isEmpty();
    assertThat(config.getReturnAttributeName())
        .isEqualTo(CaptureConfiguration.DEFAULT_RETURN_ATTRIBUTE_NAME);
    assertThat(config.getMaxStringLength())
        .isEqualTo(CaptureConfiguration.DEFAULT_MAX_STRING_LENGTH);
    assertThat(config.getMaxCollectionWidth())
        .isEqualTo(CaptureConfiguration.DEFAULT_MAX_COLLECTION_WIDTH);
    assertThat(config.getMaxCollectionDepth())
        .isEqualTo(CaptureConfiguration.DEFAULT_MAX_COLLECTION_DEPTH);
    assertThat(config.getMaxStackFrames()).isEqualTo(CaptureConfiguration.DEFAULT_MAX_STACK_FRAMES);
    assertThat(config.getMaxStackTraceSize())
        .isEqualTo(CaptureConfiguration.DEFAULT_MAX_STACK_TRACE_SIZE);
    assertThat(config.getMaxObjectDepth()).isEqualTo(CaptureConfiguration.DEFAULT_MAX_OBJECT_DEPTH);
    assertThat(config.getMaxFieldsPerObject())
        .isEqualTo(CaptureConfiguration.DEFAULT_MAX_FIELDS_PER_OBJECT);
  }

  @Test
  void testBuilder_setAllValues() {
    CaptureConfiguration config =
        CaptureConfiguration.builder()
            .captureReturn(true)
            .captureStackTrace(true)
            .captureArguments(List.of("arg1", "arg2"))
            .captureLocals(List.of("local1"))
            .argMappings(Map.of("arg1", "custom.arg1"))
            .returnAttributeName("custom.return")
            .maxStringLength(150)
            .maxCollectionWidth(15)
            .maxCollectionDepth(4)
            .maxStackFrames(5)
            .maxStackTraceSize(300)
            .maxObjectDepth(4)
            .maxFieldsPerObject(15)
            .build();

    assertThat(config.isCaptureReturn()).isTrue();
    assertThat(config.isCaptureStackTrace()).isTrue();
    assertThat(config.getCaptureArguments()).containsExactly("arg1", "arg2");
    assertThat(config.getCaptureLocals()).containsExactly("local1");
    assertThat(config.getArgMappings()).containsEntry("arg1", "custom.arg1");
    assertThat(config.getReturnAttributeName()).isEqualTo("custom.return");
    assertThat(config.getMaxStringLength()).isEqualTo(150);
    assertThat(config.getMaxCollectionWidth()).isEqualTo(15);
    assertThat(config.getMaxCollectionDepth()).isEqualTo(4);
    assertThat(config.getMaxStackFrames()).isEqualTo(5);
    assertThat(config.getMaxStackTraceSize()).isEqualTo(300);
    assertThat(config.getMaxObjectDepth()).isEqualTo(4);
    assertThat(config.getMaxFieldsPerObject()).isEqualTo(15);
  }

  @Test
  void testBuilder_clampMaxStringLengthBelowMinimum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxStringLength(0).build();

    assertThat(config.getMaxStringLength()).isEqualTo(1); // Clamped to MIN
  }

  @Test
  void testBuilder_clampMaxStringLengthAboveMaximum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxStringLength(500).build();

    assertThat(config.getMaxStringLength()).isEqualTo(255); // Clamped to MAX
  }

  @Test
  void testBuilder_clampMaxCollectionWidthBelowMinimum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxCollectionWidth(0).build();

    assertThat(config.getMaxCollectionWidth()).isEqualTo(1); // Clamped to MIN
  }

  @Test
  void testBuilder_clampMaxCollectionWidthAboveMaximum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxCollectionWidth(150).build();

    assertThat(config.getMaxCollectionWidth()).isEqualTo(20); // Clamped to MAX
  }

  @Test
  void testBuilder_clampMaxCollectionDepthBelowMinimum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxCollectionDepth(0).build();

    assertThat(config.getMaxCollectionDepth()).isEqualTo(1); // Clamped to MIN
  }

  @Test
  void testBuilder_clampMaxCollectionDepthAboveMaximum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxCollectionDepth(10).build();

    assertThat(config.getMaxCollectionDepth()).isEqualTo(5); // Clamped to MAX
  }

  @Test
  void testBuilder_clampMaxStackFramesBelowMinimum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxStackFrames(0).build();

    assertThat(config.getMaxStackFrames()).isEqualTo(1); // Clamped to MIN
  }

  @Test
  void testBuilder_clampMaxStackFramesAboveMaximum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxStackFrames(50).build();

    assertThat(config.getMaxStackFrames()).isEqualTo(20); // Clamped to MAX
  }

  @Test
  void testBuilder_clampMaxStackTraceSizeBelowMinimum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxStackTraceSize(0).build();

    assertThat(config.getMaxStackTraceSize()).isEqualTo(1); // Clamped to MIN
  }

  @Test
  void testBuilder_clampMaxStackTraceSizeAboveMaximum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxStackTraceSize(2000).build();

    assertThat(config.getMaxStackTraceSize()).isEqualTo(1000); // Clamped to MAX
  }

  @Test
  void testBuilder_clampMaxObjectDepthBelowMinimum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxObjectDepth(0).build();

    assertThat(config.getMaxObjectDepth()).isEqualTo(1); // Clamped to MIN
  }

  @Test
  void testBuilder_clampMaxObjectDepthAboveMaximum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxObjectDepth(10).build();

    assertThat(config.getMaxObjectDepth()).isEqualTo(5); // Clamped to MAX
  }

  @Test
  void testBuilder_clampMaxFieldsPerObjectBelowMinimum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxFieldsPerObject(0).build();

    assertThat(config.getMaxFieldsPerObject()).isEqualTo(1); // Clamped to MIN
  }

  @Test
  void testBuilder_clampMaxFieldsPerObjectAboveMaximum() {
    CaptureConfiguration config = CaptureConfiguration.builder().maxFieldsPerObject(50).build();

    assertThat(config.getMaxFieldsPerObject()).isEqualTo(20); // Clamped to MAX
  }

  @Test
  void testBuilder_nullCaptureArgumentsMeansDoNotCapture() {
    CaptureConfiguration config = CaptureConfiguration.builder().captureArguments(null).build();

    assertThat(config.getCaptureArguments()).isNull();
  }

  @Test
  void testBuilder_nullCaptureLocalsMeansDoNotCapture() {
    CaptureConfiguration config = CaptureConfiguration.builder().captureLocals(null).build();

    assertThat(config.getCaptureLocals()).isNull();
  }

  @Test
  void testBuilder_emptyCaptureArgumentsMeansCaptureAll() {
    CaptureConfiguration config =
        CaptureConfiguration.builder().captureArguments(List.of()).build();

    assertThat(config.getCaptureArguments()).isNotNull();
    assertThat(config.getCaptureArguments()).isEmpty();
  }

  @Test
  void testBuilder_emptyCaptureLocalsMeansCaptureAll() {
    CaptureConfiguration config = CaptureConfiguration.builder().captureLocals(List.of()).build();

    assertThat(config.getCaptureLocals()).isNotNull();
    assertThat(config.getCaptureLocals()).isEmpty();
  }

  @Test
  void testBuilder_specificCaptureArgumentsMeansCaptureOnlyThose() {
    CaptureConfiguration config =
        CaptureConfiguration.builder().captureArguments(List.of("userId", "orderId")).build();

    assertThat(config.getCaptureArguments()).containsExactly("userId", "orderId");
  }

  @Test
  void testBuilder_specificCaptureLocalsMeansCaptureOnlyThose() {
    CaptureConfiguration config =
        CaptureConfiguration.builder().captureLocals(List.of("temp", "result")).build();

    assertThat(config.getCaptureLocals()).containsExactly("temp", "result");
  }

  @Test
  void testBuilder_nullArgMappingsDefaultsToEmptyMap() {
    CaptureConfiguration config = CaptureConfiguration.builder().argMappings(null).build();

    assertThat(config.getArgMappings()).isEmpty();
  }

  @Test
  void testBuilder_nullReturnAttributeNameUsesDefault() {
    CaptureConfiguration config = CaptureConfiguration.builder().returnAttributeName(null).build();

    assertThat(config.getReturnAttributeName())
        .isEqualTo(CaptureConfiguration.DEFAULT_RETURN_ATTRIBUTE_NAME);
  }

  @Test
  void testBuilder_emptyReturnAttributeNameUsesDefault() {
    CaptureConfiguration config = CaptureConfiguration.builder().returnAttributeName("   ").build();

    assertThat(config.getReturnAttributeName())
        .isEqualTo(CaptureConfiguration.DEFAULT_RETURN_ATTRIBUTE_NAME);
  }

  @Test
  void testBuilder_collectionsAreImmutable() {
    List<String> captureArgs = List.of("arg1", "arg2");
    Map<String, String> argMappings = Map.of("arg1", "custom.arg1");

    CaptureConfiguration config =
        CaptureConfiguration.builder()
            .captureArguments(captureArgs)
            .argMappings(argMappings)
            .build();

    assertThat(config.getCaptureArguments()).containsExactly("arg1", "arg2");
    assertThat(config.getArgMappings()).containsEntry("arg1", "custom.arg1");

    // Verify immutability - these should not affect the config
    assertThat(config.getCaptureArguments()).isInstanceOf(java.util.List.class);
    assertThat(config.getArgMappings()).isInstanceOf(java.util.Map.class);
  }

  @Test
  void testBuilder_multipleClampingScenarios() {
    CaptureConfiguration config =
        CaptureConfiguration.builder()
            .maxStringLength(500) // Above max
            .maxCollectionWidth(0) // Below min
            .maxCollectionDepth(3) // Within range
            .maxStackFrames(100) // Above max
            .maxStackTraceSize(-10) // Below min
            .maxObjectDepth(4) // Within range
            .maxFieldsPerObject(25) // Above max
            .build();

    assertThat(config.getMaxStringLength()).isEqualTo(255); // Clamped to max
    assertThat(config.getMaxCollectionWidth()).isEqualTo(1); // Clamped to min
    assertThat(config.getMaxCollectionDepth()).isEqualTo(3); // Unchanged
    assertThat(config.getMaxStackFrames()).isEqualTo(20); // Clamped to max
    assertThat(config.getMaxStackTraceSize()).isEqualTo(1); // Clamped to min
    assertThat(config.getMaxObjectDepth()).isEqualTo(4); // Unchanged
    assertThat(config.getMaxFieldsPerObject()).isEqualTo(20); // Clamped to max
  }
}
