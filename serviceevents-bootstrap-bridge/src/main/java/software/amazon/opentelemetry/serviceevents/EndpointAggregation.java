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

package software.amazon.opentelemetry.serviceevents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Endpoint aggregation data. */
public class EndpointAggregation {
  private final String route;
  private final String method;
  private volatile String operation;
  private int count;
  private int faultCount;
  private int errorCount;
  private long sumDurationNs;
  private final List<Long> durations = Collections.synchronizedList(new ArrayList<Long>());
  private final ConcurrentHashMap<String, ConcurrentHashMap<String, EndpointErrorData>>
      errorBreakdown =
          new ConcurrentHashMap<String, ConcurrentHashMap<String, EndpointErrorData>>();
  private final List<IncidentExemplar> incidentExemplars =
      Collections.synchronizedList(new ArrayList<IncidentExemplar>());
  private static final int MAX_EXEMPLARS_PER_TRIGGER = 10;

  /**
   * Upper bound on the number of raw duration samples retained between flushes. The samples are
   * only replayed into a bucketed histogram at flush time, and {@code sumDurationNs}/{@code count}
   * are tracked separately, so capping the raw list bounds memory under pathological throughput
   * without affecting the emitted sum or request count. The cap is high enough that normal traffic
   * (well under one flush interval) is unaffected.
   */
  private static final int MAX_DURATION_SAMPLES = 200_000;

  public EndpointAggregation(String route, String method) {
    this.route = route;
    this.method = method;
  }

  /** Returns the operation name, falling back to method + " " + route if not explicitly set. */
  public String getOperation() {
    String op = operation;
    return op != null ? op : method + " " + route;
  }

  public void setOperation(String operation) {
    this.operation = operation;
  }

  public synchronized void incrementCount() {
    count++;
  }

  public synchronized void incrementFaultCount() {
    faultCount++;
  }

  public synchronized void incrementErrorCount() {
    errorCount++;
  }

  public synchronized void recordDuration(long durationNs) {
    sumDurationNs += durationNs;
    // sumDurationNs/count above stay exact; only the raw sample list (replayed into a histogram at
    // flush) is bounded, to cap memory if a flush is delayed under extreme throughput.
    if (durations.size() < MAX_DURATION_SAMPLES) {
      durations.add(Long.valueOf(durationNs));
    }
  }

  public void recordError(
      String failureType, String errorKey, String errorType, String functionId) {
    ConcurrentHashMap<String, EndpointErrorData> errorMap = errorBreakdown.get(failureType);
    if (errorMap == null) {
      errorMap = new ConcurrentHashMap<String, EndpointErrorData>();
      ConcurrentHashMap<String, EndpointErrorData> existing =
          errorBreakdown.putIfAbsent(failureType, errorMap);
      if (existing != null) {
        errorMap = existing;
      }
    }
    EndpointErrorData data = errorMap.get(errorKey);
    if (data == null) {
      data = new EndpointErrorData(errorType, functionId);
      EndpointErrorData existingData = errorMap.putIfAbsent(errorKey, data);
      if (existingData != null) {
        data = existingData;
      }
    }
    data.incrementCount();
  }

  public String getRoute() {
    return route;
  }

  public String getMethod() {
    return method;
  }

  public synchronized int getCount() {
    return count;
  }

  public synchronized int getFaultCount() {
    return faultCount;
  }

  public synchronized int getErrorCount() {
    return errorCount;
  }

  public synchronized long getSumDurationNs() {
    return sumDurationNs;
  }

  public List<Long> getDurations() {
    // durations is a Collections.synchronizedList; the copy constructor iterates it, which per the
    // synchronizedList contract must hold the list's monitor to avoid
    // ConcurrentModificationException.
    synchronized (durations) {
      return new ArrayList<Long>(durations);
    }
  }

  public Map<String, Map<String, EndpointErrorData>> getErrorBreakdown() {
    Map<String, Map<String, EndpointErrorData>> result =
        new HashMap<String, Map<String, EndpointErrorData>>();
    for (Map.Entry<String, ConcurrentHashMap<String, EndpointErrorData>> entry :
        errorBreakdown.entrySet()) {
      result.put(entry.getKey(), new HashMap<String, EndpointErrorData>(entry.getValue()));
    }
    return result;
  }

  public void addIncidentExemplar(
      String snapshotId, String triggerType, String severity, long timestamp) {
    synchronized (incidentExemplars) {
      int count = 0;
      for (IncidentExemplar ex : incidentExemplars) {
        if (triggerType.equals(ex.triggerType)) {
          count++;
        }
      }
      if (count < MAX_EXEMPLARS_PER_TRIGGER) {
        incidentExemplars.add(new IncidentExemplar(snapshotId, triggerType, severity, timestamp));
      }
    }
  }

  public List<IncidentExemplar> getIncidentExemplars() {
    return new ArrayList<IncidentExemplar>(incidentExemplars);
  }
}
