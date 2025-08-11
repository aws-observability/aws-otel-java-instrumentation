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
    validate("https://sqs.us-east-1.amazonaws.com/123412341234/Q_Name-5", "Q_Name-5");
    validate("https://sqs.af-south-1.amazonaws.com/999999999999/-_ThisIsValid", "-_ThisIsValid");
    validate("http://sqs.eu-west-3.amazonaws.com/000000000000/FirstQueue", "FirstQueue");
    validate("sqs.sa-east-1.amazonaws.com/123456781234/SecondQueue", "SecondQueue");
  }

  @Test
  public void testSqsClientSpanLegacyFormatUrls() {
    validate("https://ap-northeast-2.queue.amazonaws.com/123456789012/MyQueue", "MyQueue");
    validate("http://cn-northwest-1.queue.amazonaws.com/123456789012/MyQueue", "MyQueue");
    validate("http://cn-north-1.queue.amazonaws.com/123456789012/MyQueue", "MyQueue");
    validate(
        "ap-south-1.queue.amazonaws.com/123412341234/MyLongerQueueNameHere",
        "MyLongerQueueNameHere");
    validate("https://queue.amazonaws.com/123456789012/MyQueue", "MyQueue");
  }

  @Test
  public void testSqsClientSpanCustomUrls() {
    validate("http://127.0.0.1:1212/123456789012/MyQueue", "MyQueue");
    validate("https://127.0.0.1:1212/123412341234/RRR", "RRR");
    validate("127.0.0.1:1212/123412341234/QQ", "QQ");
    validate("https://amazon.com/123412341234/BB", "BB");
  }

  @Test
  public void testSqsClientSpanLongUrls() {
    String queueName = "a".repeat(80);
    validate("http://127.0.0.1:1212/123456789012/" + queueName, queueName);

    String queueNameTooLong = "a".repeat(81);
    validate("http://127.0.0.1:1212/123456789012/" + queueNameTooLong, null);
  }

  @Test
  public void testClientSpanSqsInvalidOrEmptyUrls() {
    validate(null, null);
    validate("", null);
    validate(" ", null);
    validate("/", null);
    validate("//", null);
    validate("///", null);
    validate("//asdf", null);
    validate("/123412341234/as&df", null);
    validate("invalidUrl", null);
    validate("https://www.amazon.com", null);
    validate("https://sqs.us-east-1.amazonaws.com/123412341234/.", null);
    validate("https://sqs.us-east-1.amazonaws.com/12/Queue", null);
    validate("https://sqs.us-east-1.amazonaws.com/A/A", null);
    validate("https://sqs.us-east-1.amazonaws.com/123412341234/A/ThisShouldNotBeHere", null);
  }

  private void validate(String url, String expectedName) {
    assertThat(SqsUrlParser.getQueueName(url)).isEqualTo(Optional.ofNullable(expectedName));
  }
}
