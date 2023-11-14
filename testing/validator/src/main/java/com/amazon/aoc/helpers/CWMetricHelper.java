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

package com.amazon.aoc.helpers;

import com.amazon.aoc.callers.ICaller;
import com.amazon.aoc.fileconfigs.FileConfig;
import com.amazon.aoc.models.Context;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.Metric;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** helper class for getting expected metrics from templates. */
public class CWMetricHelper {
  private static final String DEFAULT_DIMENSION_NAME = "OTelLib";
  MustacheHelper mustacheHelper = new MustacheHelper();

  /**
   * get expected metrics from template with injecting context.
   *
   * @param context testing context
   * @param expectedMetric expected template
   * @param caller http caller, none caller, could be null
   * @return list of metrics
   * @throws Exception when caller throws exception or template can not be found
   */
  public List<Metric> listExpectedMetrics(
      Context context, FileConfig expectedMetric, ICaller caller) throws Exception {
    // call endpoint
    if (caller != null) {
      caller.callSampleApp();
    }

    // get expected metrics as yaml from config
    String yamlExpectedMetrics = mustacheHelper.render(expectedMetric, context);

    // load metrics from yaml
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    List<Metric> expectedMetricList =
        mapper.readValue(
            yamlExpectedMetrics.getBytes(StandardCharsets.UTF_8),
            new TypeReference<List<Metric>>() {});

    if (context.getIsRollup()) {
      return this.rollupMetric(expectedMetricList);
    }

    return expectedMetricList;
  }

  /**
   * rollup the metrics 1. all dimension rollup 2. zero dimension rollup 3. single dimension rollup
   * Ex. A metric A with dimensions OtelLib, Dimension_1, Dimension_2 will be rolled up to four
   * metrics: 1. All dimension rollup: A [OtelLib, Dimension_1, Dimension_2]. 2. Zero dimension
   * rollup: A [OtelLib]. 3. Single dimension rollup: A [OtelLib, Dimension_1], A [OtelLib,
   * Dimension_2]
   *
   * @param metricList after rolled up
   * @return list of rolled up metrics
   */
  private List<Metric> rollupMetric(List<Metric> metricList) {
    List<Metric> rollupMetricList = new ArrayList<>();
    for (Metric metric : metricList) {
      Dimension otellibDimension = new Dimension();
      boolean otelLibDimensionExisted = false;

      if (metric.getDimensions().size() > 0) {
        // get otellib dimension out
        // assuming the first dimension is otellib, if not the validation fails
        otellibDimension = metric.getDimensions().get(0);
        otelLibDimensionExisted = otellibDimension.getName().equals(DEFAULT_DIMENSION_NAME);
      }

      if (otelLibDimensionExisted) {
        metric.getDimensions().remove(0);
      }

      // all dimension rollup
      Metric allDimensionsMetric = new Metric();
      allDimensionsMetric.setMetricName(metric.getMetricName());
      allDimensionsMetric.setNamespace(metric.getNamespace());
      allDimensionsMetric.setDimensions(metric.getDimensions());

      if (otelLibDimensionExisted) {
        allDimensionsMetric
            .getDimensions()
            .add(
                new Dimension()
                    .withName(otellibDimension.getName())
                    .withValue(otellibDimension.getValue()));
      }
      rollupMetricList.add(allDimensionsMetric);

      // zero dimension rollup
      Metric zeroDimensionMetric = new Metric();
      zeroDimensionMetric.setNamespace(metric.getNamespace());
      zeroDimensionMetric.setMetricName(metric.getMetricName());

      if (otelLibDimensionExisted) {
        zeroDimensionMetric.setDimensions(
            Arrays.asList(
                new Dimension()
                    .withName(otellibDimension.getName())
                    .withValue(otellibDimension.getValue())));
      }
      rollupMetricList.add(zeroDimensionMetric);

      // single dimension rollup
      for (Dimension dimension : metric.getDimensions()) {
        Metric singleDimensionMetric = new Metric();
        singleDimensionMetric.setNamespace(metric.getNamespace());
        singleDimensionMetric.setMetricName(metric.getMetricName());
        if (otelLibDimensionExisted) {
          singleDimensionMetric.setDimensions(
              Arrays.asList(
                  new Dimension()
                      .withName(otellibDimension.getName())
                      .withValue(otellibDimension.getValue())));
        }
        singleDimensionMetric.getDimensions().add(dimension);
        rollupMetricList.add(singleDimensionMetric);
      }
    }

    return rollupMetricList;
  }
}
