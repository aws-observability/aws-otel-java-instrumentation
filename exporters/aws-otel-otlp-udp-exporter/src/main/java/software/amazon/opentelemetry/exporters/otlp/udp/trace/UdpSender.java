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

package software.amazon.opentelemetry.exporters.otlp.udp.trace;

import io.opentelemetry.sdk.common.CompletableResultCode;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class represents a UDP sender that sends data to a specified endpoint. It is used to send
 * data to a remote host and port using UDP protocol.
 */
public class UdpSender {
  private static final Logger logger = Logger.getLogger(UdpSender.class.getName());

  private DatagramSocket socket;
  private final InetSocketAddress endpoint;

  public UdpSender(String host, int port) {
    this.endpoint = new InetSocketAddress(host, port);
    try {
      this.socket = new DatagramSocket();
    } catch (SocketException e) {
      logger.log(Level.SEVERE, "Exception while instantiating UdpSender socket.", e);
    }
  }

  public CompletableResultCode shutdown() {
    try {
      if (socket == null) {
        return CompletableResultCode.ofSuccess();
      }
      socket.close();
      return CompletableResultCode.ofSuccess();
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Exception while closing UdpSender socket.", e);
      return CompletableResultCode.ofFailure();
    }
  }

  public void send(byte[] data) {
    if (socket == null) {
      logger.log(Level.WARNING, "UdpSender socket is null. Cannot send data.");
      return;
    }
    DatagramPacket packet = new DatagramPacket(data, data.length, endpoint);
    try {
      socket.send(packet);
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Exception while sending data.", e);
    }
  }

  // Visible for testing
  InetSocketAddress getEndpoint() {
    return endpoint;
  }
}
