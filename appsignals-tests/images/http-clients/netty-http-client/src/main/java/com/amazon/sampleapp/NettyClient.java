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

package com.amazon.sampleapp;

import static spark.Spark.get;
import static spark.Spark.ipAddress;
import static spark.Spark.port;
import static spark.Spark.post;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import java.net.URI;

public class NettyClient {
  static final String LISTENING_ADDRESS = "0.0.0.0";
  static final int LISTENING_PORT = 8080;

  public static void main(String[] args) throws Exception {
    port(LISTENING_PORT);
    // This is the IP address of the network interface that should listen for connections
    // 0.0.0.0 means all interfaces
    ipAddress(LISTENING_ADDRESS);
    get(
        "/success",
        (req, res) -> {
          HttpResponse response =
              nettyHttpClient(
                  new DefaultFullHttpRequest(
                      HttpVersion.HTTP_1_1, HttpMethod.GET, "http://backend:8080/backend/success"));
          res.status(response.getStatus().code());
          return "";
        });
    get(
        "/backend/success",
        (req, res) -> {
          res.status(200);
          res.body("Health Check");
          return res.body();
        });

    get(
        "/error",
        (req, res) -> {
          HttpResponse response =
              nettyHttpClient(
                  new DefaultFullHttpRequest(
                      HttpVersion.HTTP_1_1, HttpMethod.GET, "http://backend:8080/backend/error"));
          res.status(response.getStatus().code());
          return "";
        });
    get(
        "/backend/error",
        (req, res) -> {
          res.status(400);
          res.body("Bad Request");
          return res.body();
        });

    get(
        "/fault",
        (req, res) -> {
          HttpResponse response =
              nettyHttpClient(
                  new DefaultFullHttpRequest(
                      HttpVersion.HTTP_1_1, HttpMethod.GET, "http://backend:8080/backend/fault"));
          res.status(response.getStatus().code());
          return "";
        });
    get(
        "/backend/fault",
        (req, res) -> {
          res.status(500);
          res.body("Internal Error");
          return res.body();
        });

    post(
        "/success/postmethod",
        (req, res) -> {
          HttpResponse response =
              nettyHttpClient(
                  new DefaultFullHttpRequest(
                      HttpVersion.HTTP_1_1,
                      HttpMethod.POST,
                      "http://backend:8080/backend/success/postmethod",
                      Unpooled.wrappedBuffer(req.body().getBytes()),
                      true));
          res.status(response.getStatus().code());
          return "";
        });
    post(
        "/backend/success/postmethod",
        (req, res) -> {
          res.status(200);
          res.body(req.body());
          return res.body();
        });
    post(
        "/error/postmethod",
        (req, res) -> {
          HttpResponse response =
              nettyHttpClient(
                  new DefaultFullHttpRequest(
                      HttpVersion.HTTP_1_1,
                      HttpMethod.POST,
                      "http://backend:8080/backend/error/postmethod",
                      Unpooled.wrappedBuffer(req.body().getBytes()),
                      true));
          res.status(response.getStatus().code());
          return "";
        });
    post(
        "/backend/error/postmethod",
        (req, res) -> {
          res.status(400);
          res.body(req.body());
          return res.body();
        });
    post(
        "/fault/postmethod",
        (req, res) -> {
          HttpResponse response =
              nettyHttpClient(
                  new DefaultFullHttpRequest(
                      HttpVersion.HTTP_1_1,
                      HttpMethod.POST,
                      "http://backend:8080/backend/fault/postmethod",
                      Unpooled.wrappedBuffer(req.body().getBytes()),
                      true));
          res.status(response.getStatus().code());
          return "";
        });
    post(
        "/backend/fault/postmethod",
        (req, res) -> {
          res.status(500);
          res.body(req.body());
          return res.body();
        });
  }

  private static HttpResponse nettyHttpClient(HttpRequest request) throws Exception {
    // Configure the client.
    EventLoopGroup group = new NioEventLoopGroup();
    Channel ch;
    HttpResponse response = null;
    HttpClientHandler handler = new HttpClientHandler();

    var uri = URI.create(request.uri());
    try {
      Bootstrap b = new Bootstrap();
      b.group(group)
          .option(ChannelOption.SO_KEEPALIVE, true)
          .channel(NioSocketChannel.class)
          .handler(
              new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                  ch.pipeline()
                      .addLast(new HttpClientCodec(), new HttpContentDecompressor(), handler);
                }
              });
      // Make connection
      var port = uri.getPort();
      var host = uri.getHost();

      if (port == -1) {
        port = uri.getScheme().equals("https") ? 443 : 80;
      }
      ch = b.connect(host, port).sync().channel();
      // Create the HOST http request header
      // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Host
      var hostHeaderValue = String.format("%s:%s", host, port);

      request.headers().set(HttpHeaderNames.HOST, hostHeaderValue);
      request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
      request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);

      // Send HTTP request.
      ch.writeAndFlush(request);

      // Wait for the server to close connection.
      ch.closeFuture().sync();
      response = handler.getHttpResponse();
    } finally {
      group.shutdownGracefully();
    }
    return response;
  }
}
