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

package com.amazon.aoc.services;

import com.amazonaws.services.xray.AWSXRay;
import com.amazonaws.services.xray.AWSXRayClientBuilder;
import com.amazonaws.services.xray.model.BatchGetTracesRequest;
import com.amazonaws.services.xray.model.BatchGetTracesResult;
import com.amazonaws.services.xray.model.GetTraceSummariesRequest;
import com.amazonaws.services.xray.model.GetTraceSummariesResult;
import com.amazonaws.services.xray.model.Trace;
import com.amazonaws.services.xray.model.TraceSummary;
import java.util.Date;
import java.util.List;
import org.joda.time.DateTime;

public class XRayService {
  private AWSXRay awsxRay;
  private final int SEARCH_PERIOD = 60;
  public static String DEFAULT_TRACE_ID = "1-00000000-000000000000000000000000";

  public XRayService(String region) {
    awsxRay = AWSXRayClientBuilder.standard().withRegion(region).build();
  }

  /**
   * List trace objects by ids.
   *
   * @param traceIdList trace id list
   * @return trace object list
   */
  public List<Trace> listTraceByIds(List<String> traceIdList) {
    BatchGetTracesResult batchGetTracesResult =
        awsxRay.batchGetTraces(new BatchGetTracesRequest().withTraceIds(traceIdList));

    return batchGetTracesResult.getTraces();
  }

  // Search for traces generated within the last 60 second.
  public List<TraceSummary> searchTraces() {
    Date currentDate = new Date();
    Date pastDate = new DateTime(currentDate).minusSeconds(SEARCH_PERIOD).toDate();
    GetTraceSummariesResult traceSummaryResult =
        awsxRay.getTraceSummaries(
            new GetTraceSummariesRequest().withStartTime(pastDate).withEndTime(currentDate));
    return traceSummaryResult.getTraceSummaries();
  }
}
