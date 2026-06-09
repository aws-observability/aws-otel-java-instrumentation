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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model.snapshot;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Container for captured data at different points during method execution.
 *
 * <p>For method-level captures: {@code entry} holds arguments, {@code methodReturn} holds return
 * value/throwable.
 *
 * <p>For line-level captures: {@code lines} maps line numbers to their captured local variables.
 */
public final class Captures {

  private final CapturedContext entry;
  private final CapturedContext methodReturn;
  private final Map<Integer, CapturedContext> lines;

  private Captures(
      CapturedContext entry, CapturedContext methodReturn, Map<Integer, CapturedContext> lines) {
    this.entry = entry;
    this.methodReturn = methodReturn;
    this.lines =
        lines != null
            ? Collections.unmodifiableMap(lines)
            : Collections.<Integer, CapturedContext>emptyMap();
  }

  public CapturedContext getEntry() {
    return entry;
  }

  public CapturedContext getMethodReturn() {
    return methodReturn;
  }

  public Map<Integer, CapturedContext> getLines() {
    return lines;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private CapturedContext entry;
    private CapturedContext methodReturn;
    private Map<Integer, CapturedContext> lines;

    private Builder() {}

    public Builder entry(CapturedContext entry) {
      this.entry = entry;
      return this;
    }

    public Builder methodReturn(CapturedContext methodReturn) {
      this.methodReturn = methodReturn;
      return this;
    }

    public Builder addLine(int lineNumber, CapturedContext context) {
      if (this.lines == null) {
        this.lines = new HashMap<>();
      }
      this.lines.put(lineNumber, context);
      return this;
    }

    public Captures build() {
      return new Captures(entry, methodReturn, lines);
    }
  }
}
