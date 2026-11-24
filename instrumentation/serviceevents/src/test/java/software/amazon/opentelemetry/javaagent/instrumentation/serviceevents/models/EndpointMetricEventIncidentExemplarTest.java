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

package software.amazon.opentelemetry.javaagent.instrumentation.serviceevents.models;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for IncidentExemplarEntry and incidents_exemplar in EndpointMetricEvent. */
class EndpointMetricEventIncidentExemplarTest {

  // ========================================================================
  // IncidentExemplarEntry
  // ========================================================================

  @Test
  void testIncidentExemplarEntryConstruction() {
    EndpointMetricEvent.IncidentExemplarEntry entry =
        new EndpointMetricEvent.IncidentExemplarEntry(
            "snap_abc123", "exception", "critical", 1700000000L);

    assertEquals("snap_abc123", entry.getSnapshotId());
    assertEquals("exception", entry.getTriggerType());
    assertEquals("critical", entry.getSeverity());
    assertEquals(1700000000L, entry.getTimestamp());
  }

  @Test
  void testIncidentExemplarEntryDefaultConstructorAndSetters() {
    EndpointMetricEvent.IncidentExemplarEntry entry =
        new EndpointMetricEvent.IncidentExemplarEntry();

    entry.setSnapshotId("snap_xyz");
    entry.setTriggerType("latency");
    entry.setSeverity("medium");
    entry.setTimestamp(9999L);

    assertEquals("snap_xyz", entry.getSnapshotId());
    assertEquals("latency", entry.getTriggerType());
    assertEquals("medium", entry.getSeverity());
    assertEquals(9999L, entry.getTimestamp());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testIncidentExemplarEntryToMap() {
    EndpointMetricEvent.IncidentExemplarEntry entry =
        new EndpointMetricEvent.IncidentExemplarEntry(
            "snap_abc123", "exception", "critical", 1700000000L);

    Map<String, Object> map = entry.toMap();

    assertEquals("snap_abc123", map.get("snapshot_id"));
    assertEquals("exception", map.get("trigger_type"));
    assertEquals("critical", map.get("severity"));
    assertEquals(1700000000L, map.get("timestamp"));
    assertEquals(4, map.size());
  }

  // ========================================================================
  // EndpointMetricEvent builder with incidentsExemplar
  // ========================================================================

  @Test
  void testBuilderIncidentsExemplar() {
    List<EndpointMetricEvent.IncidentExemplarEntry> entries = new ArrayList<>();
    entries.add(
        new EndpointMetricEvent.IncidentExemplarEntry("snap_1", "exception", "critical", 1000L));
    entries.add(
        new EndpointMetricEvent.IncidentExemplarEntry("snap_2", "latency", "medium", 2000L));

    EndpointMetricEvent event = EndpointMetricEvent.builder().incidentsExemplar(entries).build();

    List<EndpointMetricEvent.IncidentExemplarEntry> result = event.getIncidentsExemplar();
    assertNotNull(result);
    assertEquals(2, result.size());
    assertEquals("snap_1", result.get(0).getSnapshotId());
    assertEquals("snap_2", result.get(1).getSnapshotId());
  }

  @Test
  void testDefaultIncidentsExemplarIsEmptyList() {
    EndpointMetricEvent event = new EndpointMetricEvent();
    assertNotNull(event.getIncidentsExemplar());
    assertTrue(event.getIncidentsExemplar().isEmpty());
  }

  // ========================================================================
  // EndpointMetricEvent.toMap() includes incidents_exemplar
  // ========================================================================

  @Test
  @SuppressWarnings("unchecked")
  void testToMapIncludesIncidentsExemplar() {
    List<EndpointMetricEvent.IncidentExemplarEntry> entries = new ArrayList<>();
    entries.add(
        new EndpointMetricEvent.IncidentExemplarEntry("snap_aaa", "exception", "high", 5000L));

    EndpointMetricEvent event =
        EndpointMetricEvent.builder()
            .method("GET")
            .route("/api/test")
            .errorBreakdown(new ArrayList<>())
            .incidentsExemplar(entries)
            .build();

    Map<String, Object> map = event.toMap();

    assertTrue(map.containsKey("incidents_exemplar"));
    List<Map<String, Object>> exemplarMaps =
        (List<Map<String, Object>>) map.get("incidents_exemplar");
    assertEquals(1, exemplarMaps.size());
    assertEquals("snap_aaa", exemplarMaps.get(0).get("snapshot_id"));
    assertEquals("exception", exemplarMaps.get(0).get("trigger_type"));
    assertEquals("high", exemplarMaps.get(0).get("severity"));
    assertEquals(5000L, exemplarMaps.get(0).get("timestamp"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testToMapEmptyIncidentsExemplar() {
    EndpointMetricEvent event =
        EndpointMetricEvent.builder()
            .errorBreakdown(new ArrayList<>())
            .incidentsExemplar(new ArrayList<>())
            .build();

    Map<String, Object> map = event.toMap();

    assertTrue(map.containsKey("incidents_exemplar"));
    List<Map<String, Object>> exemplarMaps =
        (List<Map<String, Object>>) map.get("incidents_exemplar");
    assertNotNull(exemplarMaps);
    assertTrue(exemplarMaps.isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testToMapMultipleExemplars() {
    List<EndpointMetricEvent.IncidentExemplarEntry> entries =
        Arrays.asList(
            new EndpointMetricEvent.IncidentExemplarEntry("snap_1", "exception", "critical", 1000L),
            new EndpointMetricEvent.IncidentExemplarEntry("snap_2", "latency", "medium", 2000L),
            new EndpointMetricEvent.IncidentExemplarEntry("snap_3", "exception", "low", 3000L));

    EndpointMetricEvent event =
        EndpointMetricEvent.builder()
            .errorBreakdown(new ArrayList<>())
            .incidentsExemplar(entries)
            .build();

    Map<String, Object> map = event.toMap();

    List<Map<String, Object>> exemplarMaps =
        (List<Map<String, Object>>) map.get("incidents_exemplar");
    assertEquals(3, exemplarMaps.size());

    // Verify ordering preserved
    assertEquals("snap_1", exemplarMaps.get(0).get("snapshot_id"));
    assertEquals("snap_2", exemplarMaps.get(1).get("snapshot_id"));
    assertEquals("snap_3", exemplarMaps.get(2).get("snapshot_id"));
  }

  // ========================================================================
  // Getter/Setter
  // ========================================================================

  @Test
  void testSetIncidentsExemplar() {
    EndpointMetricEvent event = new EndpointMetricEvent();
    List<EndpointMetricEvent.IncidentExemplarEntry> entries = new ArrayList<>();
    entries.add(new EndpointMetricEvent.IncidentExemplarEntry("snap_set", "latency", "low", 7777L));

    event.setIncidentsExemplar(entries);

    assertEquals(1, event.getIncidentsExemplar().size());
    assertEquals("snap_set", event.getIncidentsExemplar().get(0).getSnapshotId());
  }
}
