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

package software.amazon.opentelemetry.javaagent.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

public class SqsUrlParserTest {

  @Test
  public void testSqsClientSpanBasicUrls() {
    validateGetQueueName("https://sqs.us-east-1.amazonaws.com/123412341234/Q_Name-5", "Q_Name-5");
    validateGetQueueName(
        "https://sqs.af-south-1.amazonaws.com/999999999999/-_ThisIsValid", "-_ThisIsValid");
    validateGetQueueName(
        "http://sqs.eu-west-3.amazonaws.com/000000000000/FirstQueue", "FirstQueue");
    validateGetQueueName("sqs.sa-east-1.amazonaws.com/123456781234/SecondQueue", "SecondQueue");
  }

  @Test
  public void testSqsClientSpanLegacyFormatUrls() {
    validateGetQueueName(
        "https://ap-northeast-2.queue.amazonaws.com/123456789012/MyQueue", "MyQueue");
    validateGetQueueName(
        "http://cn-northwest-1.queue.amazonaws.com/123456789012/MyQueue", "MyQueue");
    validateGetQueueName("http://cn-north-1.queue.amazonaws.com/123456789012/MyQueue", "MyQueue");
    validateGetQueueName(
        "ap-south-1.queue.amazonaws.com/123412341234/MyLongerQueueNameHere",
        "MyLongerQueueNameHere");
    validateGetQueueName("https://queue.amazonaws.com/123456789012/MyQueue", "MyQueue");
  }

  @Test
  public void testSqsClientSpanCustomUrls() {
    validateGetQueueName("http://127.0.0.1:1212/123456789012/MyQueue", "MyQueue");
    validateGetQueueName("https://127.0.0.1:1212/123412341234/RRR", "RRR");
    validateGetQueueName("127.0.0.1:1212/123412341234/QQ", "QQ");
    validateGetQueueName("https://amazon.com/123412341234/BB", "BB");
  }

  @Test
  public void testSqsClientSpanLongUrls() {
    String queueName = "a".repeat(80);
    validateGetQueueName("http://127.0.0.1:1212/123456789012/" + queueName, queueName);

    String queueNameTooLong = "a".repeat(81);
    validateGetQueueName("http://127.0.0.1:1212/123456789012/" + queueNameTooLong, null);
  }

  @Test
  public void testClientSpanSqsInvalidOrEmptyUrls() {
    validateGetQueueName(null, null);
    validateGetQueueName("", null);
    validateGetQueueName(" ", null);
    validateGetQueueName("/", null);
    validateGetQueueName("//", null);
    validateGetQueueName("///", null);
    validateGetQueueName("//asdf", null);
    validateGetQueueName("/123412341234/as&df", null);
    validateGetQueueName("invalidUrl", null);
    validateGetQueueName("https://www.amazon.com", null);
    validateGetQueueName("https://sqs.us-east-1.amazonaws.com/123412341234/.", null);
    validateGetQueueName("https://sqs.us-east-1.amazonaws.com/12/Queue", null);
    validateGetQueueName("https://sqs.us-east-1.amazonaws.com/A/A", null);
    validateGetQueueName(
        "https://sqs.us-east-1.amazonaws.com/123412341234/A/ThisShouldNotBeHere", null);
  }

  @Test
  public void testClientSpanSqsAccountId() {
    validateGetAccountId(null, null);
    validateGetAccountId("", null);
    validateGetAccountId(" ", null);
    validateGetAccountId("/", null);
    validateGetAccountId("//", null);
    validateGetAccountId("///", null);
    validateGetAccountId("//asdf", null);
    validateGetAccountId("/123412341234/as&df", null);
    validateGetAccountId("invalidUrl", null);
    validateGetAccountId("https://www.amazon.com", null);
    validateGetAccountId("https://sqs.us-east-1.amazonaws.com/123412341234/Queue", "123412341234");
    validateGetAccountId("https://sqs.us-east-1.amazonaws.com/12341234/Queue", null);
    validateGetAccountId("https://sqs.us-east-1.amazonaws.com/1234123412xx/Queue", null);
    validateGetAccountId("https://sqs.us-east-1.amazonaws.com/1234123412xx", null);
  }

  @Test
  public void testClientSpanSqsRegion() {
    validateGetRegion(null, null);
    validateGetRegion("", null);
    validateGetRegion(" ", null);
    validateGetRegion("/", null);
    validateGetRegion("//", null);
    validateGetRegion("///", null);
    validateGetRegion("//asdf", null);
    validateGetRegion("/123412341234/as&df", null);
    validateGetRegion("invalidUrl", null);
    validateGetRegion("https://www.amazon.com", null);
    validateGetRegion("https://sqs.us-east-1.amazonaws.com/123412341234/Queue", "us-east-1");
  }

  private void validateGetRegion(String url, String expectedRegion) {
    assertThat(SqsUrlParser.getRegion(url)).isEqualTo(Optional.ofNullable(expectedRegion));
  }

  private void validateGetAccountId(String url, String expectedAccountId) {
    assertThat(SqsUrlParser.getAccountId(url)).isEqualTo(Optional.ofNullable(expectedAccountId));
  }

  private void validateGetQueueName(String url, String expectedName) {
    assertThat(SqsUrlParser.getQueueName(url)).isEqualTo(Optional.ofNullable(expectedName));
  }
}
