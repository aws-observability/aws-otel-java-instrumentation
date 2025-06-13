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

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public class BedrockJsonParser {

  // Prevent instantiation
  private BedrockJsonParser() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * This class is internal and is hence not for public use. Its APIs are unstable and can change at
   * any time.
   */
  public static LlmJson parse(String jsonString) {
    JsonParser parser = new JsonParser(jsonString);
    Map<String, Object> jsonBody = parser.parse();
    return new LlmJson(jsonBody);
  }

  static class JsonParser {
    private final String json;
    private int position;

    public JsonParser(String json) {
      this.json = json.trim();
      this.position = 0;
    }

    private void skipWhitespace() {
      while (position < json.length() && Character.isWhitespace(json.charAt(position))) {
        position++;
      }
    }

    private char currentChar() {
      return json.charAt(position);
    }

    private static boolean isHexDigit(char c) {
      return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private void expect(char c) {
      skipWhitespace();
      if (currentChar() != c) {
        throw new IllegalArgumentException(
            "Expected '" + c + "' but found '" + currentChar() + "'");
      }
      position++;
    }

    private String readString() {
      skipWhitespace();
      expect('"'); // Ensure the string starts with a quote
      StringBuilder result = new StringBuilder();
      while (currentChar() != '"') {
        // Handle escape sequences
        if (currentChar() == '\\') {
          position++; // Move past the backslash
          if (position >= json.length()) {
            throw new IllegalArgumentException("Unexpected end of input in string escape sequence");
          }
          char escapeChar = currentChar();
          switch (escapeChar) {
            case '"':
            case '\\':
            case '/':
              result.append(escapeChar);
              break;
            case 'b':
              result.append('\b');
              break;
            case 'f':
              result.append('\f');
              break;
            case 'n':
              result.append('\n');
              break;
            case 'r':
              result.append('\r');
              break;
            case 't':
              result.append('\t');
              break;
            case 'u': // Unicode escape sequence
              if (position + 4 >= json.length()) {
                throw new IllegalArgumentException("Invalid unicode escape sequence in string");
              }
              char[] hexChars = new char[4];
              for (int i = 0; i < 4; i++) {
                position++; // Move to the next character
                char hexChar = json.charAt(position);
                if (!isHexDigit(hexChar)) {
                  throw new IllegalArgumentException(
                      "Invalid hexadecimal digit in unicode escape sequence");
                }
                hexChars[i] = hexChar;
              }
              int unicodeValue = Integer.parseInt(new String(hexChars), 16);
              result.append((char) unicodeValue);
              break;
            default:
              throw new IllegalArgumentException("Invalid escape character: \\" + escapeChar);
          }
          position++;
        } else {
          result.append(currentChar());
          position++;
        }
      }
      position++; // Skip closing quote
      return result.toString();
    }

    private Object readValue() {
      skipWhitespace();
      char c = currentChar();

      if (c == '"') {
        return readString();
      } else if (Character.isDigit(c)) {
        return readScopedNumber();
      } else if (c == '{') {
        return readObject(); // JSON Objects
      } else if (c == '[') {
        return readArray(); // JSON Arrays
      } else if (json.startsWith("true", position)) {
        position += 4;
        return true;
      } else if (json.startsWith("false", position)) {
        position += 5;
        return false;
      } else if (json.startsWith("null", position)) {
        position += 4;
        return null; // JSON null
      } else {
        throw new IllegalArgumentException("Unexpected character: " + c);
      }
    }

    private Number readScopedNumber() {
      int start = position;

      // Consume digits and the optional decimal point
      while (position < json.length()
          && (Character.isDigit(json.charAt(position)) || json.charAt(position) == '.')) {
        position++;
      }

      String number = json.substring(start, position);

      if (number.contains(".")) {
        double value = Double.parseDouble(number);
        if (value < 0.0 || value > 1.0) {
          throw new IllegalArgumentException(
              "Value out of bounds for Bedrock Floating Point Attribute: " + number);
        }
        return value;
      } else {
        return Integer.parseInt(number);
      }
    }

    private Map<String, Object> readObject() {
      Map<String, Object> map = new HashMap<>();
      expect('{');
      skipWhitespace();
      while (currentChar() != '}') {
        String key = readString();
        expect(':');
        Object value = readValue();
        map.put(key, value);
        skipWhitespace();
        if (currentChar() == ',') {
          position++;
        }
      }
      position++; // Skip closing brace
      return map;
    }

    private List<Object> readArray() {
      List<Object> list = new ArrayList<>();
      expect('[');
      skipWhitespace();
      while (currentChar() != ']') {
        list.add(readValue());
        skipWhitespace();
        if (currentChar() == ',') {
          position++;
        }
      }
      position++;
      return list;
    }

    public Map<String, Object> parse() {
      return readObject();
    }
  }

  // Resolves paths in a JSON structure
  static class JsonPathResolver {

    // Private constructor to prevent instantiation
    private JsonPathResolver() {
      throw new UnsupportedOperationException("Utility class");
    }

    public static Object resolvePath(LlmJson llmJson, String... paths) {
      for (String path : paths) {
        Object value = resolvePath(llmJson.getJsonBody(), path);
        if (value != null) {
          return value;
        }
      }
      return null;
    }

    private static Object resolvePath(Map<String, Object> json, String path) {
      String[] keys = path.split("/");
      Object current = json;

      for (String key : keys) {
        if (key.isEmpty()) {
          continue;
        }

        if (current instanceof Map) {
          current = ((Map<?, ?>) current).get(key);
        } else if (current instanceof List) {
          try {
            int index = Integer.parseInt(key);
            current = ((List<?>) current).get(index);
          } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return null;
          }
        } else {
          return null;
        }

        if (current == null) {
          return null;
        }
      }
      return current;
    }
  }

  /**
   * This class is internal and is hence not for public use. Its APIs are unstable and can change at
   * any time.
   */
  public static class LlmJson {
    private final Map<String, Object> jsonBody;

    public LlmJson(Map<String, Object> jsonBody) {
      this.jsonBody = jsonBody;
    }

    public Map<String, Object> getJsonBody() {
      return jsonBody;
    }
  }
}
