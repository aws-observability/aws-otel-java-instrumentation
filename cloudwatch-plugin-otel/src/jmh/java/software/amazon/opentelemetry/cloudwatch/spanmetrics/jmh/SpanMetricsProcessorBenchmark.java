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

package software.amazon.opentelemetry.cloudwatch.spanmetrics.jmh;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import software.amazon.opentelemetry.cloudwatch.spanmetrics.SpanMetricsProcessor;

/** Per-span hot-path benchmarks for onStart and onEnd. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class SpanMetricsProcessorBenchmark {

  private SpanMetricsProcessor processor;
  private ReadableSpan httpSpan;
  private ReadableSpan dbLegacySpan;
  private ReadWriteSpan writableSpan;

  @Setup(Level.Trial)
  public void setup() {
    BenchmarkSupport.bindSdk();
    processor = new SpanMetricsProcessor();
    // Hand-written fixed spans (not mocks) so onEnd measures the processor, not a mock proxy.
    httpSpan = new FixedReadableSpan(BenchmarkSupport.httpServerSpan());
    dbLegacySpan = new FixedReadableSpan(BenchmarkSupport.databaseLegacySpan());
    // onStart's target: a real recording SDK span, so we measure the true cost of our two
    // setAttribute calls against a production ReadWriteSpan (not a mock's proxy overhead).
    writableSpan = BenchmarkSupport.recordingSpan();
    // Warm the lazy instrument init so measured onEnd calls take the steady-state path.
    processor.onEnd(httpSpan);
    processor.onEnd(dbLegacySpan);
  }

  @Benchmark
  public void onEnd_httpServer() {
    processor.onEnd(httpSpan);
  }

  @Benchmark
  public void onEnd_databaseLegacyFallback() {
    processor.onEnd(dbLegacySpan);
  }

  @Benchmark
  @Threads(4)
  public void onEnd_httpServer_4threads() {
    processor.onEnd(httpSpan);
  }

  @Benchmark
  public void onStart() {
    processor.onStart(Context.root(), writableSpan);
  }
}
