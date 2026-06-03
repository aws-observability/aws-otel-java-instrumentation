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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Complete instrumentation configuration parsed from API response.
 *
 * <p>Supports both PROBE (permanent) and BREAKPOINT (temporary) instrumentation types.
 *
 * <p>For Java, the location is identified by:
 *
 * <ul>
 *   <li>CodeUnit: Package name (e.g., "com.example")
 *   <li>ClassName: Class name (e.g., "OrderService") - required for Java
 *   <li>MethodName: Method name (e.g., "processOrder")
 *   <li>LineNumber: Line number (optional - 0 or missing = method-level, &gt;0 = line-level)
 * </ul>
 */
public final class InstrumentationConfiguration {
  private static final Logger logger =
      Logger.getLogger(InstrumentationConfiguration.class.getName());
  private static final int DEFAULT_MAX_HITS = 100;
  private static final int MIN_MAX_HITS = 1;
  private static final int MAX_MAX_HITS = 1000;

  private final String codeUnit;
  private final String className;
  private final String methodName;
  private final int lineNumber;
  private final String filePath;
  private final CaptureConfiguration captureConfig;
  private final String locationHash;
  private final InstrumentationType instrumentationType;
  private final String instrumentationName;
  private final Instant expiresAt;
  private final int maxHits;
  private final List<Map<String, String>> attributeFilters;
  private final String arn;
  private final Instant createdAt;
  private final String signalType;

  private InstrumentationConfiguration(
      String codeUnit,
      String className,
      String methodName,
      int lineNumber,
      String filePath,
      CaptureConfiguration captureConfig,
      String locationHash,
      InstrumentationType instrumentationType,
      String instrumentationName,
      Instant expiresAt,
      int maxHits,
      List<Map<String, String>> attributeFilters,
      String arn,
      Instant createdAt,
      String signalType) {
    this.codeUnit = codeUnit;
    this.className = className;
    this.methodName = methodName;
    this.lineNumber = lineNumber;
    this.filePath = filePath;
    this.captureConfig = captureConfig;
    this.locationHash = locationHash;
    this.instrumentationType = instrumentationType;
    this.instrumentationName = instrumentationName;
    this.expiresAt = expiresAt;
    this.maxHits = maxHits;
    this.attributeFilters = Collections.unmodifiableList(new ArrayList<>(attributeFilters));
    this.arn = arn;
    this.createdAt = createdAt;
    this.signalType = signalType;
  }

  /**
   * Parse API response into InstrumentationConfiguration.
   *
   * <p>Example API response structure:
   *
   * <pre>
   * {
   *   "InstrumentationType": "BREAKPOINT",
   *   "SignalType": "SNAPSHOT",
   *   "Location": {
   *     "CodeLocation": {
   *       "Language": "Java",
   *       "CodeUnit": "com.example",
   *       "ClassName": "OrderService",
   *       "MethodName": "processOrder",
   *       "FilePath": "OrderService.java",
   *       "LineNumber": 42
   *     }
   *   },
   *   "LocationHash": "abc123",
   *   "ExpiresAt": "2026-01-23T10:36:51-08:00",
   *   "CaptureConfiguration": {...}
   * }
   * </pre>
   *
   * @param apiConfig Configuration item from API response
   * @return InstrumentationConfiguration instance, or null if parsing fails
   */
  @SuppressWarnings("unchecked")
  public static InstrumentationConfiguration fromApiConfig(Map<String, Object> apiConfig) {
    try {
      // Extract Location union — unwrap CodeLocation from the union wrapper
      Map<String, Object> locationUnion = (Map<String, Object>) apiConfig.get("Location");
      if (locationUnion == null) {
        logger.warning("Missing Location in API config");
        return null;
      }

      Map<String, Object> location = (Map<String, Object>) locationUnion.get("CodeLocation");
      if (location == null) {
        logger.fine("Skipping non-CodeLocation config");
        return null;
      }

      // Check language - only process Java configurations
      String language = (String) location.get("Language");
      if (language == null || !language.equalsIgnoreCase("java")) {
        return null;
      }

      // Extract location fields from CodeLocation struct
      String codeUnit = (String) location.get("CodeUnit");
      String className = (String) location.get("ClassName");
      String methodName = (String) location.get("MethodName");
      String filePath = (String) location.get("FilePath");

      // Validate required fields - for Java, all three are required
      if (codeUnit == null
          || codeUnit.isEmpty()
          || className == null
          || className.isEmpty()
          || methodName == null
          || methodName.isEmpty()) {
        logger.log(
            Level.WARNING,
            "Invalid location: CodeUnit=''{0}'', ClassName=''{1}'', MethodName=''{2}''. Skipping.",
            new Object[] {codeUnit, className, methodName});
        return null;
      }

      // Parse instrumentation type
      String typeStr = (String) apiConfig.get("InstrumentationType");
      InstrumentationType type;
      if (typeStr == null || typeStr.isEmpty()) {
        type = InstrumentationType.BREAKPOINT; // Default for backward compatibility
      } else {
        try {
          type = InstrumentationType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
          logger.log(
              Level.WARNING,
              "Invalid InstrumentationType ''{0}'' for {1}.{2}.{3}. Defaulting to BREAKPOINT.",
              new Object[] {typeStr, codeUnit, className, methodName});
          type = InstrumentationType.BREAKPOINT;
        }
      }

      // Parse instrumentation name (optional for PROBE)
      String instrName = (String) apiConfig.get("InstrumentationName");
      if (instrName == null) {
        instrName = ""; // Default to empty string
      }

      // Parse line number (optional - 0 if missing means method-level)
      int lineNum = safeInt(location.get("LineNumber"), 0);
      if (type == InstrumentationType.PROBE && lineNum > 0) {
        logger.log(
            Level.INFO,
            "{0} instrumentation for {1}.{2}.{3} has LineNumber {4}. Forcing to 0 (method-level only).",
            new Object[] {type, codeUnit, className, methodName, lineNum});
        lineNum = 0;
      }

      if (lineNum < 0) {
        logger.log(
            Level.WARNING,
            "Invalid LineNumber {0} for {1}.{2}.{3}. Must be >= 0. Skipping.",
            new Object[] {lineNum, codeUnit, className, methodName});
        return null;
      }

      // Parse capture configuration — unwrap CodeCapture from the union wrapper
      Map<String, Object> captureUnion =
          (Map<String, Object>) apiConfig.get("CaptureConfiguration");
      Map<String, Object> configData;
      if (captureUnion != null && captureUnion.containsKey("CodeCapture")) {
        configData = (Map<String, Object>) captureUnion.get("CodeCapture");
        if (configData == null) {
          configData = Collections.emptyMap();
        }
      } else if (captureUnion != null && !captureUnion.isEmpty()) {
        // Backward compatibility: treat flat structure as CodeCapture directly
        configData = captureUnion;
      } else {
        configData = Collections.emptyMap();
      }

      CaptureConfiguration captureConfig = parseCaptureConfiguration(configData);

      // Parse LocationHash
      String locationHash = (String) apiConfig.get("LocationHash");
      if (locationHash == null) {
        locationHash = "";
      }

      // Parse expiry (ignored for PROBE)
      Instant expiresAt = null;
      if (type == InstrumentationType.BREAKPOINT) {
        Object expiresValue = apiConfig.get("ExpiresAt");
        if (expiresValue != null) {
          expiresAt = parseTimestamp(expiresValue);
        }
      }

      // Parse max hits - PROBE configs are unlimited, BREAKPOINT configs have limits
      int maxHits;
      if (type == InstrumentationType.PROBE) {
        maxHits = Integer.MAX_VALUE; // Permanent configs are unlimited
      } else {
        // BREAKPOINT - parse from config or use default
        maxHits = DEFAULT_MAX_HITS;
        Map<String, Object> captureLimits = (Map<String, Object>) configData.get("CaptureLimits");
        if (captureLimits != null) {
          maxHits = clampMaxHits(safeInt(captureLimits.get("MaxHits"), DEFAULT_MAX_HITS));
        }
      }

      // Parse attribute filters
      List<Map<String, String>> filters =
          (List<Map<String, String>>) apiConfig.get("AttributeFilters");
      if (filters == null) {
        filters = Collections.emptyList();
      }

      // Parse ARN (optional)
      String arn = (String) apiConfig.get("ARN");

      // Parse CreatedAt (optional)
      Instant createdAt = null;
      Object createdAtValue = apiConfig.get("CreatedAt");
      if (createdAtValue != null) {
        createdAt = parseTimestamp(createdAtValue);
      }

      // Parse SignalType (optional, defaults to SNAPSHOT)
      String signalType = (String) apiConfig.getOrDefault("SignalType", "SNAPSHOT");

      return new InstrumentationConfiguration(
          codeUnit,
          className,
          methodName,
          lineNum,
          filePath,
          captureConfig,
          locationHash,
          type,
          instrName,
          expiresAt,
          maxHits,
          filters,
          arn,
          createdAt,
          signalType);

    } catch (ClassCastException e) {
      logger.log(Level.SEVERE, "Type mismatch in API config: {0}", e.getMessage());
      return null;
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Unexpected error parsing API config", e);
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static CaptureConfiguration parseCaptureConfiguration(Map<String, Object> configData) {
    Map<String, Object> captureLimits = (Map<String, Object>) configData.get("CaptureLimits");
    if (captureLimits == null) {
      captureLimits = Collections.emptyMap();
    }

    // Distinguish missing (null = do not capture) from present-but-empty ([] = capture all).
    // configData.containsKey() checks if the API response included the field at all.
    List<String> captureArgs = null;
    if (configData.containsKey("CaptureArguments")) {
      Object rawArgs = configData.get("CaptureArguments");
      captureArgs = (rawArgs instanceof List) ? (List<String>) rawArgs : Collections.emptyList();
    }

    List<String> captureLocals = null;
    if (configData.containsKey("CaptureLocals")) {
      Object rawLocals = configData.get("CaptureLocals");
      captureLocals =
          (rawLocals instanceof List) ? (List<String>) rawLocals : Collections.emptyList();
    }

    Map<String, String> argMappings = (Map<String, String>) configData.get("arg_mappings");
    if (argMappings == null) {
      argMappings = Collections.emptyMap();
    }

    return CaptureConfiguration.builder()
        .captureReturn(safeBoolean(configData.get("CaptureReturn"), false))
        .captureStackTrace(safeBoolean(configData.get("CaptureStackTrace"), false))
        .captureArguments(captureArgs)
        .captureLocals(captureLocals)
        .argMappings(argMappings)
        .returnAttributeName(
            (String)
                configData.getOrDefault(
                    "return_attribute_name", CaptureConfiguration.DEFAULT_RETURN_ATTRIBUTE_NAME))
        .maxStringLength(
            safeInt(
                captureLimits.get("MaxStringLength"),
                CaptureConfiguration.DEFAULT_MAX_STRING_LENGTH))
        .maxCollectionWidth(
            safeInt(
                captureLimits.get("MaxCollectionWidth"),
                CaptureConfiguration.DEFAULT_MAX_COLLECTION_WIDTH))
        .maxCollectionDepth(
            safeInt(
                captureLimits.get("MaxCollectionDepth"),
                CaptureConfiguration.DEFAULT_MAX_COLLECTION_DEPTH))
        .maxStackFrames(
            safeInt(
                captureLimits.get("MaxStackFrames"), CaptureConfiguration.DEFAULT_MAX_STACK_FRAMES))
        .maxStackTraceSize(
            safeInt(
                captureLimits.get("MaxStackTraceSize"),
                CaptureConfiguration.DEFAULT_MAX_STACK_TRACE_SIZE))
        .maxObjectDepth(
            safeInt(
                captureLimits.get("MaxObjectDepth"), CaptureConfiguration.DEFAULT_MAX_OBJECT_DEPTH))
        .maxFieldsPerObject(
            safeInt(
                captureLimits.get("MaxFieldsPerObject"),
                CaptureConfiguration.DEFAULT_MAX_FIELDS_PER_OBJECT))
        .build();
  }

  private static Instant parseTimestamp(Object value) {
    if (value instanceof Number) {
      // Unix timestamp (seconds)
      long seconds = ((Number) value).longValue();
      return Instant.ofEpochSecond(seconds);
    } else if (value instanceof String) {
      // ISO 8601 string
      try {
        return Instant.parse((String) value);
      } catch (DateTimeParseException e) {
        logger.log(Level.WARNING, "Invalid timestamp format: {0}", value);
        return null;
      }
    }
    return null;
  }

  private static int safeInt(Object value, int defaultValue) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    return defaultValue;
  }

  private static boolean safeBoolean(Object value, boolean defaultValue) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    } else if (value instanceof String) {
      return Boolean.parseBoolean((String) value);
    }
    return defaultValue;
  }

  private static int clampMaxHits(int value) {
    if (value < MIN_MAX_HITS) {
      logger.log(
          Level.WARNING,
          "maxHits={0} below minimum {1}, clamping to {1}",
          new Object[] {value, MIN_MAX_HITS});
      return MIN_MAX_HITS;
    }
    if (value > MAX_MAX_HITS) {
      logger.log(
          Level.WARNING,
          "maxHits={0} above maximum {1}, clamping to {1}",
          new Object[] {value, MAX_MAX_HITS});
      return MAX_MAX_HITS;
    }
    return value;
  }

  // Property getters

  /** Get fully qualified class name: codeUnit.className */
  public String getFullyQualifiedClassName() {
    return codeUnit + "." + className;
  }

  /** Get unique key for the method: codeUnit.className.methodName */
  public String getMethodKey() {
    return codeUnit + "." + className + "." + methodName;
  }

  /** Get unique key for this instrumentation point: methodKey:lineNumber */
  public String getInstrumentationKey() {
    return getMethodKey() + ":" + lineNumber;
  }

  public boolean isValid() {
    return lineNumber >= 0;
  }

  public boolean isLineLevel() {
    return lineNumber > 0;
  }

  public boolean isMethodLevel() {
    return lineNumber == 0;
  }

  public boolean isPermanent() {
    return instrumentationType == InstrumentationType.PROBE;
  }

  public boolean isTemporary() {
    return instrumentationType == InstrumentationType.BREAKPOINT;
  }

  // Standard getters

  public String getCodeUnit() {
    return codeUnit;
  }

  public String getClassName() {
    return className;
  }

  public String getMethodName() {
    return methodName;
  }

  public int getLineNumber() {
    return lineNumber;
  }

  public String getFilePath() {
    return filePath;
  }

  public CaptureConfiguration getCaptureConfig() {
    return captureConfig;
  }

  public String getLocationHash() {
    return locationHash;
  }

  public InstrumentationType getInstrumentationType() {
    return instrumentationType;
  }

  public String getInstrumentationName() {
    return instrumentationName;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public int getMaxHits() {
    return maxHits;
  }

  public List<Map<String, String>> getAttributeFilters() {
    return attributeFilters;
  }

  public String getArn() {
    return arn;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getSignalType() {
    return signalType;
  }

  @Override
  public String toString() {
    return "InstrumentationConfiguration{"
        + "type="
        + instrumentationType
        + ", method="
        + getMethodKey()
        + ", line="
        + lineNumber
        + ", hash='"
        + locationHash
        + '\''
        + '}';
  }
}
