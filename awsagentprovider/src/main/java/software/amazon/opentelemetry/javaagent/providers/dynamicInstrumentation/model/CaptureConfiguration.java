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

package software.amazon.opentelemetry.javaagent.providers.dynamicInstrumentation.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configuration for parameter and return value capture in dynamic instrumentation.
 *
 * <p>Defines what data to capture when instrumentation points are hit, including function
 * arguments, return values, local variables, and stack traces. All limits are validated and clamped
 * to safe ranges to prevent excessive memory usage.
 */
public final class CaptureConfiguration {
  private static final Logger logger = Logger.getLogger(CaptureConfiguration.class.getName());

  // Default capture configuration limits
  static final int DEFAULT_MAX_STRING_LENGTH = 255;
  static final int DEFAULT_MAX_COLLECTION_WIDTH = 20;
  static final int DEFAULT_MAX_COLLECTION_DEPTH = 3;
  static final int DEFAULT_MAX_STACK_FRAMES = 20;
  static final int DEFAULT_MAX_STACK_TRACE_SIZE = 200;
  static final int DEFAULT_MAX_OBJECT_DEPTH = 3;
  static final int DEFAULT_MAX_FIELDS_PER_OBJECT = 20;
  static final String DEFAULT_RETURN_ATTRIBUTE_NAME = "aws.di.return_value";

  // Validation ranges
  private static final int MIN_MAX_STRING_LENGTH = 1;
  private static final int MAX_MAX_STRING_LENGTH = 255;
  private static final int MIN_MAX_COLLECTION_WIDTH = 1;
  private static final int MAX_MAX_COLLECTION_WIDTH = 20;
  private static final int MIN_MAX_COLLECTION_DEPTH = 1;
  private static final int MAX_MAX_COLLECTION_DEPTH = 5;
  private static final int MIN_MAX_STACK_FRAMES = 1;
  private static final int MAX_MAX_STACK_FRAMES = 20;
  private static final int MIN_MAX_STACK_TRACE_SIZE = 1;
  private static final int MAX_MAX_STACK_TRACE_SIZE = 1000;
  private static final int MIN_MAX_OBJECT_DEPTH = 1;
  private static final int MAX_MAX_OBJECT_DEPTH = 5;
  private static final int MIN_MAX_FIELDS_PER_OBJECT = 1;
  private static final int MAX_MAX_FIELDS_PER_OBJECT = 20;

  // Capture flags
  private final boolean captureReturn;
  private final boolean captureStackTrace;
  // null = field absent from API (do not capture)
  // empty list = field present as [] (capture all)
  // non-empty list = capture only the named items
  private final List<String> captureArguments;
  private final List<String> captureLocals;

  // Attribute mappings
  private final Map<String, String> argMappings;
  private final String returnAttributeName;

  // Validated and clamped limits
  private final int maxStringLength;
  private final int maxCollectionWidth;
  private final int maxCollectionDepth;
  private final int maxStackFrames;
  private final int maxStackTraceSize;
  private final int maxObjectDepth;
  private final int maxFieldsPerObject;

  private CaptureConfiguration(Builder builder) {
    this.captureReturn = builder.captureReturn;
    this.captureStackTrace = builder.captureStackTrace;
    this.captureArguments =
        builder.captureArguments == null
            ? null
            : Collections.unmodifiableList(new ArrayList<>(builder.captureArguments));
    this.captureLocals =
        builder.captureLocals == null
            ? null
            : Collections.unmodifiableList(new ArrayList<>(builder.captureLocals));
    this.argMappings = Collections.unmodifiableMap(new HashMap<>(builder.argMappings));

    // Validate and set return attribute name
    if (builder.returnAttributeName == null || builder.returnAttributeName.trim().isEmpty()) {
      logger.log(
          Level.WARNING,
          "Invalid returnAttributeName ''{0}'', using default",
          builder.returnAttributeName);
      this.returnAttributeName = DEFAULT_RETURN_ATTRIBUTE_NAME;
    } else {
      this.returnAttributeName = builder.returnAttributeName;
    }

    // Clamp all numeric limits to valid ranges
    this.maxStringLength =
        clamp(
            builder.maxStringLength,
            MIN_MAX_STRING_LENGTH,
            MAX_MAX_STRING_LENGTH,
            DEFAULT_MAX_STRING_LENGTH,
            "maxStringLength");
    this.maxCollectionWidth =
        clamp(
            builder.maxCollectionWidth,
            MIN_MAX_COLLECTION_WIDTH,
            MAX_MAX_COLLECTION_WIDTH,
            DEFAULT_MAX_COLLECTION_WIDTH,
            "maxCollectionWidth");
    this.maxCollectionDepth =
        clamp(
            builder.maxCollectionDepth,
            MIN_MAX_COLLECTION_DEPTH,
            MAX_MAX_COLLECTION_DEPTH,
            DEFAULT_MAX_COLLECTION_DEPTH,
            "maxCollectionDepth");
    this.maxStackFrames =
        clamp(
            builder.maxStackFrames,
            MIN_MAX_STACK_FRAMES,
            MAX_MAX_STACK_FRAMES,
            DEFAULT_MAX_STACK_FRAMES,
            "maxStackFrames");
    this.maxStackTraceSize =
        clamp(
            builder.maxStackTraceSize,
            MIN_MAX_STACK_TRACE_SIZE,
            MAX_MAX_STACK_TRACE_SIZE,
            DEFAULT_MAX_STACK_TRACE_SIZE,
            "maxStackTraceSize");
    this.maxObjectDepth =
        clamp(
            builder.maxObjectDepth,
            MIN_MAX_OBJECT_DEPTH,
            MAX_MAX_OBJECT_DEPTH,
            DEFAULT_MAX_OBJECT_DEPTH,
            "maxObjectDepth");
    this.maxFieldsPerObject =
        clamp(
            builder.maxFieldsPerObject,
            MIN_MAX_FIELDS_PER_OBJECT,
            MAX_MAX_FIELDS_PER_OBJECT,
            DEFAULT_MAX_FIELDS_PER_OBJECT,
            "maxFieldsPerObject");
  }

  /** Clamp value to valid range. Logs warnings if value is out of range. */
  private static int clamp(int value, int minVal, int maxVal, int defaultVal, String name) {
    if (value < minVal) {
      logger.log(
          Level.WARNING,
          "{0}={1} below minimum {2}, clamping to {2}",
          new Object[] {name, value, minVal});
      return minVal;
    }
    if (value > maxVal) {
      logger.log(
          Level.WARNING,
          "{0}={1} above maximum {2}, clamping to {2}",
          new Object[] {name, value, maxVal});
      return maxVal;
    }
    return value;
  }

  public static Builder builder() {
    return new Builder();
  }

  // Public getters
  public boolean isCaptureReturn() {
    return captureReturn;
  }

  public boolean isCaptureStackTrace() {
    return captureStackTrace;
  }

  public List<String> getCaptureArguments() {
    return captureArguments;
  }

  public List<String> getCaptureLocals() {
    return captureLocals;
  }

  public Map<String, String> getArgMappings() {
    return argMappings;
  }

  public String getReturnAttributeName() {
    return returnAttributeName;
  }

  public int getMaxStringLength() {
    return maxStringLength;
  }

  public int getMaxCollectionWidth() {
    return maxCollectionWidth;
  }

  public int getMaxCollectionDepth() {
    return maxCollectionDepth;
  }

  public int getMaxStackFrames() {
    return maxStackFrames;
  }

  public int getMaxStackTraceSize() {
    return maxStackTraceSize;
  }

  public int getMaxObjectDepth() {
    return maxObjectDepth;
  }

  public int getMaxFieldsPerObject() {
    return maxFieldsPerObject;
  }

  /** Builder for CaptureConfiguration with fluent API. */
  public static final class Builder {
    private boolean captureReturn = false;
    private boolean captureStackTrace = false;
    private List<String> captureArguments = null;
    private List<String> captureLocals = null;
    private Map<String, String> argMappings = new HashMap<>();
    private String returnAttributeName = DEFAULT_RETURN_ATTRIBUTE_NAME;
    private int maxStringLength = DEFAULT_MAX_STRING_LENGTH;
    private int maxCollectionWidth = DEFAULT_MAX_COLLECTION_WIDTH;
    private int maxCollectionDepth = DEFAULT_MAX_COLLECTION_DEPTH;
    private int maxStackFrames = DEFAULT_MAX_STACK_FRAMES;
    private int maxStackTraceSize = DEFAULT_MAX_STACK_TRACE_SIZE;
    private int maxObjectDepth = DEFAULT_MAX_OBJECT_DEPTH;
    private int maxFieldsPerObject = DEFAULT_MAX_FIELDS_PER_OBJECT;

    private Builder() {}

    public Builder captureReturn(boolean captureReturn) {
      this.captureReturn = captureReturn;
      return this;
    }

    public Builder captureStackTrace(boolean captureStackTrace) {
      this.captureStackTrace = captureStackTrace;
      return this;
    }

    public Builder captureArguments(List<String> captureArguments) {
      this.captureArguments = captureArguments;
      return this;
    }

    public Builder captureLocals(List<String> captureLocals) {
      this.captureLocals = captureLocals;
      return this;
    }

    public Builder argMappings(Map<String, String> argMappings) {
      this.argMappings = argMappings != null ? argMappings : new HashMap<>();
      return this;
    }

    public Builder returnAttributeName(String returnAttributeName) {
      this.returnAttributeName = returnAttributeName;
      return this;
    }

    public Builder maxStringLength(int maxStringLength) {
      this.maxStringLength = maxStringLength;
      return this;
    }

    public Builder maxCollectionWidth(int maxCollectionWidth) {
      this.maxCollectionWidth = maxCollectionWidth;
      return this;
    }

    public Builder maxCollectionDepth(int maxCollectionDepth) {
      this.maxCollectionDepth = maxCollectionDepth;
      return this;
    }

    public Builder maxStackFrames(int maxStackFrames) {
      this.maxStackFrames = maxStackFrames;
      return this;
    }

    public Builder maxStackTraceSize(int maxStackTraceSize) {
      this.maxStackTraceSize = maxStackTraceSize;
      return this;
    }

    public Builder maxObjectDepth(int maxObjectDepth) {
      this.maxObjectDepth = maxObjectDepth;
      return this;
    }

    public Builder maxFieldsPerObject(int maxFieldsPerObject) {
      this.maxFieldsPerObject = maxFieldsPerObject;
      return this;
    }

    public CaptureConfiguration build() {
      return new CaptureConfiguration(this);
    }
  }
}
