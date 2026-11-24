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

package software.amazon.opentelemetry.appsignals.test.di;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.opentelemetry.appsignals.test.utils.ResourceScopeLog;

/**
 * Tests that DI capture limits are enforced correctly via OTLP.
 *
 * <p>The breakpoint configs intentionally request limits above the allowed maximum (e.g.,
 * MaxStringLength=9999, MaxCollectionWidth=9999). The agent must clamp these to the enforced
 * maximums.
 *
 * <p>Current enforced maximums (from CaptureConfiguration):
 *
 * <ul>
 *   <li>MAX_MAX_STRING_LENGTH = 255
 *   <li>MAX_MAX_COLLECTION_WIDTH = 20
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DICaptureLimitsTest extends DIContractTestBase {

  /** Enforced maximum for string capture length. */
  private static final int ENFORCED_MAX_STRING_LENGTH = 255;

  /** Enforced maximum for collection width (number of elements captured). */
  private static final int ENFORCED_MAX_COLLECTION_WIDTH = 20;

  @Test
  @Order(1)
  @SuppressWarnings("unchecked")
  void stringValueTruncatedAtEnforcedMaximum() throws Exception {
    // Endpoint passes a 500-char string to processLongString().
    // Config requests MaxStringLength=9999, agent clamps to 255.
    sendRequest("/limits-string");
    ResourceScopeLog log = waitForSnapshotWithArgument("processLongString", "longString");

    Map<String, Object> body = getBody(log);
    Map<String, Object> captures = (Map<String, Object>) body.get("captures");
    assertNotNull(captures, "Snapshot must have captures");

    Map<String, Object> entry = (Map<String, Object>) captures.get("entry");
    assertNotNull(entry, "Snapshot must have entry captures");

    Map<String, Object> arguments = (Map<String, Object>) entry.get("arguments");
    assertNotNull(arguments, "Entry must have arguments");

    // Java agent uses actual parameter names as keys
    Map<String, Object> longStringArg = (Map<String, Object>) arguments.get("longString");
    assertNotNull(longStringArg, "Expected 'longString' argument to be captured");

    // The captured string value should be truncated to exactly ENFORCED_MAX_STRING_LENGTH
    String capturedValue = (String) longStringArg.get("value");
    assertNotNull(capturedValue, "Captured string value should not be null");
    assertEquals(
        ENFORCED_MAX_STRING_LENGTH,
        capturedValue.length(),
        String.format(
            "String should be truncated at enforced max %d, but was %d. "
                + "If this limit has changed, update ENFORCED_MAX_STRING_LENGTH.",
            ENFORCED_MAX_STRING_LENGTH, capturedValue.length()));

    // The snapshot should indicate truncation occurred
    Boolean truncated = (Boolean) longStringArg.get("truncated");
    assertTrue(truncated != null && truncated, "Captured string should be marked as truncated");
  }

  @Test
  @Order(2)
  @SuppressWarnings("unchecked")
  void collectionElementsCappedAtEnforcedMaximum() throws Exception {
    // Endpoint passes a 50-element list to processLargeCollection().
    // Config requests MaxCollectionWidth=9999, agent clamps to 20.
    sendRequest("/limits-collection");
    ResourceScopeLog log = waitForSnapshotWithArgument("processLargeCollection", "largeList");

    Map<String, Object> body = getBody(log);
    Map<String, Object> captures = (Map<String, Object>) body.get("captures");
    assertNotNull(captures, "Snapshot must have captures");

    Map<String, Object> entry = (Map<String, Object>) captures.get("entry");
    assertNotNull(entry, "Snapshot must have entry captures");

    Map<String, Object> arguments = (Map<String, Object>) entry.get("arguments");
    assertNotNull(arguments, "Entry must have arguments");

    // Java agent uses actual parameter names as keys
    Map<String, Object> largeListArg = (Map<String, Object>) arguments.get("largeList");
    assertNotNull(largeListArg, "Expected 'largeList' argument to be captured");

    // The captured elements should contain exactly ENFORCED_MAX_COLLECTION_WIDTH items
    List<?> elements = (List<?>) largeListArg.get("elements");
    assertNotNull(elements, "Captured collection should have 'elements'");
    assertEquals(
        ENFORCED_MAX_COLLECTION_WIDTH,
        elements.size(),
        String.format(
            "Collection should be capped at enforced max %d elements, but had %d. "
                + "If this limit has changed, update ENFORCED_MAX_COLLECTION_WIDTH.",
            ENFORCED_MAX_COLLECTION_WIDTH, elements.size()));

    // The snapshot should report the original collection size
    Object sizeObj = largeListArg.get("size");
    assertNotNull(sizeObj, "Captured collection should report original size");
    int size = ((Number) sizeObj).intValue();
    assertEquals(50, size, "Original collection size should be 50");
  }
}
