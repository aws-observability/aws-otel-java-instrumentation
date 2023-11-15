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

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Scope;

public class CustomHttpServerHandler extends SimpleChannelInboundHandler<HttpObject> {
  private static final byte[] CONTENT_SUCCESS = {
    'H', 'e', 'a', 'l', 't', 'h', ' ', 'c', 'h', 'e', 'c', 'k'
  };
  private static final byte[] CONTENT_ERROR = {
    'B', 'a', 'd', ' ', 'R', 'e', 'q', 'u', 'e', 's', 't'
  };
  private static final byte[] CONTENT_FAULT = {
    'I', 'n', 't', 'e', 'r', 'n', 'a', 'l', ' ', 'e', 'r', 'r', 'o', 'r'
  };
  private static final byte[] CONTENT_NOT_FOUND = {'N', 'o', 't', ' ', 'F', 'o', 'u', 'n', 'd'};

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    ctx.flush();
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
    if (msg != null && msg instanceof HttpRequest) {
      HttpRequest req = (HttpRequest) msg;
      boolean keepAlive = HttpUtil.isKeepAlive(req);
      FullHttpResponse response =
          new DefaultFullHttpResponse(
              req.protocolVersion(),
              HttpResponseStatus.NOT_FOUND,
              Unpooled.wrappedBuffer(CONTENT_NOT_FOUND));
      if (req.method() == HttpMethod.GET) {
        if (req.uri().equals("/success")) {
          OpenTelemetry otel = GlobalOpenTelemetry.get();

          Span span =
              otel.getTracer("test-image")
                  .spanBuilder("marker-span")
                  .setSpanKind(SpanKind.INTERNAL)
                  .startSpan();
          try (Scope scope = span.makeCurrent()) {
            /** noop * */
          }
          span.end();
          response =
              new DefaultFullHttpResponse(
                  req.protocolVersion(),
                  HttpResponseStatus.OK,
                  Unpooled.wrappedBuffer(CONTENT_SUCCESS));
        } else if (req.uri().equals("/error")) {
          response =
              new DefaultFullHttpResponse(
                  req.protocolVersion(),
                  HttpResponseStatus.BAD_REQUEST,
                  Unpooled.wrappedBuffer(CONTENT_ERROR));
        } else if (req.uri().equals("/fault")) {
          response =
              new DefaultFullHttpResponse(
                  req.protocolVersion(),
                  HttpResponseStatus.INTERNAL_SERVER_ERROR,
                  Unpooled.wrappedBuffer(CONTENT_FAULT));
        }
        response
            .headers()
            .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
            .setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
      }
      if (keepAlive) {
        if (!req.protocolVersion().isKeepAliveDefault()) {
          response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
      } else {
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
      }

      ChannelFuture f = ctx.write(response);

      if (!keepAlive) {
        f.addListener(ChannelFutureListener.CLOSE);
      }
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    cause.printStackTrace();
    ctx.close();
  }
}
