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

package software.amazon.opentelemetry.javaagent.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.Attributes;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomDimensionExtractorTest {

  @Test
  void testExtractSingleCustomDimension() {
    Attributes attrs =
        Attributes.builder()
            .put("aws.application_signals.custom.dim.CarrierId", "Fedex")
            .put("aws.local.service", "payment-service")
            .build();

    Map<String, String> result = CustomDimensionExtractor.extract(attrs);

    assertEquals(1, result.size());
    assertEquals("Fedex", result.get("CarrierId"));
  }

  @Test
  void testExtractMultipleCustomDimensions() {
    Attributes attrs =
        Attributes.builder()
            .put("aws.application_signals.custom.dim.CarrierId", "Fedex")
            .put("aws.application_signals.custom.dim.Region", "US-West")
            .put("aws.application_signals.custom.dim.TenantId", "tenant-123")
            .put("aws.local.service", "payment-service")
            .put("aws.local.operation", "ProcessPayment")
            .build();

    Map<String, String> result = CustomDimensionExtractor.extract(attrs);

    assertEquals(3, result.size());
    assertEquals("Fedex", result.get("CarrierId"));
    assertEquals("US-West", result.get("Region"));
    assertEquals("tenant-123", result.get("TenantId"));
  }

  @Test
  void testExtractNoCustomDimensions() {
    Attributes attrs =
        Attributes.builder()
            .put("aws.local.service", "payment-service")
            .put("aws.local.operation", "ProcessPayment")
            .put("http.method", "POST")
            .build();

    Map<String, String> result = CustomDimensionExtractor.extract(attrs);

    assertTrue(result.isEmpty());
  }

  @Test
  void testExtractEmptyAttributes() {
    Attributes attrs = Attributes.empty();

    Map<String, String> result = CustomDimensionExtractor.extract(attrs);

    assertTrue(result.isEmpty());
  }

  @Test
  void testExtractIgnoresNonCustomDimAttributes() {
    Attributes attrs =
        Attributes.builder()
            .put("aws.application_signals.custom.dim.CarrierId", "Fedex")
            .put("aws.application_signals.dim_sets", "Service:Operation")
            .put("aws.local.service", "payment-service")
            .put("custom.dimension.NotInPrefix", "ignored")
            .build();

    Map<String, String> result = CustomDimensionExtractor.extract(attrs);

    assertEquals(1, result.size());
    assertEquals("Fedex", result.get("CarrierId"));
    assertFalse(result.containsKey("NotInPrefix"));
  }

  @Test
  void testExtractWithEmptyDimensionName() {
    // Edge case: attribute key ends with prefix but has no dimension name
    Attributes attrs =
        Attributes.builder()
            .put("aws.application_signals.custom.dim.", "ShouldBeIgnored")
            .put("aws.application_signals.custom.dim.ValidName", "ValidValue")
            .build();

    Map<String, String> result = CustomDimensionExtractor.extract(attrs);

    assertEquals(1, result.size());
    assertEquals("ValidValue", result.get("ValidName"));
  }

  @Test
  void testExtractWithNumericValues() {
    Attributes attrs =
        Attributes.builder()
            .put("aws.application_signals.custom.dim.RequestCount", 42L)
            .put("aws.application_signals.custom.dim.ErrorRate", 0.05)
            .build();

    Map<String, String> result = CustomDimensionExtractor.extract(attrs);

    assertEquals(2, result.size());
    assertEquals("42", result.get("RequestCount"));
    assertEquals("0.05", result.get("ErrorRate"));
  }

  @Test
  void testExtractWithBooleanValues() {
    Attributes attrs =
        Attributes.builder()
            .put("aws.application_signals.custom.dim.IsProduction", true)
            .put("aws.application_signals.custom.dim.HasErrors", false)
            .build();

    Map<String, String> result = CustomDimensionExtractor.extract(attrs);

    assertEquals(2, result.size());
    assertEquals("true", result.get("IsProduction"));
    assertEquals("false", result.get("HasErrors"));
  }

  @Test
  void testExtractWithSpecialCharactersInDimensionName() {
    Attributes attrs =
        Attributes.builder()
            .put("aws.application_signals.custom.dim.Carrier-Id_v2", "Fedex")
            .put("aws.application_signals.custom.dim.Region.US", "West")
            .build();

    Map<String, String> result = CustomDimensionExtractor.extract(attrs);

    assertEquals(2, result.size());
    assertEquals("Fedex", result.get("Carrier-Id_v2"));
    assertEquals("West", result.get("Region.US"));
  }
}
