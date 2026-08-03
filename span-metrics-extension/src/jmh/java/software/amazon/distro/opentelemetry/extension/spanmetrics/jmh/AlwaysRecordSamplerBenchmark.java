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

package software.amazon.distro.opentelemetry.extension.spanmetrics.jmh;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import software.amazon.distro.opentelemetry.extension.spanmetrics.AlwaysRecordSampler;

/** Measures the sampler wrapper's overhead versus calling the delegate directly. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class AlwaysRecordSamplerBenchmark {

  private Sampler delegate;
  private Sampler wrapped;

  @Setup
  public void setup() {
    // parentBased(traceIdRatio) is the realistic production sampler; 5% keeps most DROP decisions.
    delegate = Sampler.parentBased(Sampler.traceIdRatioBased(0.05));
    wrapped = AlwaysRecordSampler.create(delegate);
  }

  private SamplingResult sample(Sampler sampler) {
    return sampler.shouldSample(
        Context.root(),
        "0af7651916cd43dd8448eb211c80319c",
        "op",
        SpanKind.SERVER,
        Attributes.empty(),
        Collections.emptyList());
  }

  @Benchmark
  public void delegateDirect(Blackhole bh) {
    bh.consume(sample(delegate));
  }

  @Benchmark
  public void throughWrapper(Blackhole bh) {
    bh.consume(sample(wrapped));
  }
}
