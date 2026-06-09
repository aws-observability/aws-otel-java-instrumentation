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

/** Per-capture-point context container for dynamic instrumentation snapshots. */
public final class CapturedContext {

  private final Map<String, CapturedValue> arguments;
  private final Map<String, CapturedValue> locals;
  private final CapturedValue returnValue;
  private final CapturedThrowable throwable;

  private CapturedContext(
      Map<String, CapturedValue> arguments,
      Map<String, CapturedValue> locals,
      CapturedValue returnValue,
      CapturedThrowable throwable) {
    this.arguments = Collections.unmodifiableMap(arguments != null ? arguments : new HashMap<>());
    this.locals = Collections.unmodifiableMap(locals != null ? locals : new HashMap<>());
    this.returnValue = returnValue;
    this.throwable = throwable;
  }

  public Map<String, CapturedValue> getArguments() {
    return arguments;
  }

  public Map<String, CapturedValue> getLocals() {
    return locals;
  }

  public CapturedValue getReturnValue() {
    return returnValue;
  }

  public CapturedThrowable getThrowable() {
    return throwable;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private Map<String, CapturedValue> arguments;
    private Map<String, CapturedValue> locals;
    private CapturedValue returnValue;
    private CapturedThrowable throwable;

    private Builder() {}

    public Builder arguments(Map<String, CapturedValue> arguments) {
      this.arguments = arguments;
      return this;
    }

    public Builder locals(Map<String, CapturedValue> locals) {
      this.locals = locals;
      return this;
    }

    public Builder returnValue(CapturedValue returnValue) {
      this.returnValue = returnValue;
      return this;
    }

    public Builder throwable(CapturedThrowable throwable) {
      this.throwable = throwable;
      return this;
    }

    public CapturedContext build() {
      return new CapturedContext(arguments, locals, returnValue, throwable);
    }
  }
}
