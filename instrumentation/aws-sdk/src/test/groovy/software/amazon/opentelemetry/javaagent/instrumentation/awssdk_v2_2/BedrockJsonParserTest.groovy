/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.opentelemetry.javaagent.instrumentation.awssdk_v2_2

import spock.lang.Specification

/*
 * This is a new class created during ADOT git patching.
 */
class BedrockJsonParserTest extends Specification {
    def "should parse simple JSON object"() {
        given:
        String json = '{"key":"value"}'

        when:
        def parsedJson = BedrockJsonParser.parse(json)

        then:
        parsedJson.getJsonBody() == [key: "value"]
    }

    def "should parse nested JSON object"() {
        given:
        String json = '{"parent":{"child":"value"}}'

        when:
        def parsedJson = BedrockJsonParser.parse(json)

        then:
        def parent = parsedJson.getJsonBody().get("parent")
        parent instanceof Map
        parent["child"] == "value"
    }

    def "should parse JSON array"() {
        given:
        String json = '{"array":[1, "two", 1.0]}'

        when:
        def parsedJson = BedrockJsonParser.parse(json)

        then:
        def array = parsedJson.getJsonBody().get("array")
        array instanceof List
        array == [1, "two", 1.0]
    }

    def "should parse escape sequences"() {
        given:
        String json = '{"escaped":"Line1\\nLine2\\tTabbed\\\"Quoted\\\"\\bBackspace\\fFormfeed\\rCarriageReturn\\\\Backslash\\/Slash\\u0041"}'

        when:
        def parsedJson = BedrockJsonParser.parse(json)

        then:
        parsedJson.getJsonBody().get("escaped") ==
                "Line1\nLine2\tTabbed\"Quoted\"\bBackspace\fFormfeed\rCarriageReturn\\Backslash/SlashA"
    }

    def "should throw exception for malformed JSON"() {
        given:
        String malformedJson = '{"key":value}'

        when:
        BedrockJsonParser.parse(malformedJson)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Unexpected character")
    }

    def "should resolve path in JSON object"() {
        given:
        String json = '{"parent":{"child":{"key":"value"}}}'

        when:
        def parsedJson = BedrockJsonParser.parse(json)
        def resolvedValue = BedrockJsonParser.JsonPathResolver.resolvePath(parsedJson, "/parent/child/key")

        then:
        resolvedValue == "value"
    }

    def "should resolve path in JSON array"() {
        given:
        String json = '{"array":[{"key":"value1"}, {"key":"value2"}]}'

        when:
        def parsedJson = BedrockJsonParser.parse(json)
        def resolvedValue = BedrockJsonParser.JsonPathResolver.resolvePath(parsedJson, "/array/1/key")

        then:
        resolvedValue == "value2"
    }

    def "should return null for invalid path resolution"() {
        given:
        String json = '{"parent":{"child":{"key":"value"}}}'

        when:
        def parsedJson = BedrockJsonParser.parse(json)
        def resolvedValue = BedrockJsonParser.JsonPathResolver.resolvePath(parsedJson, "/invalid/path")

        then:
        resolvedValue == null
    }
}
