/*
 * Copyright 2017 Nordstrom, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nordstrom.xrpc.server.http;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;

/** Container for utility methods and helpers. */
public final class Recipes {
  private Recipes() {}

  public static enum ContentType {
    Application_Json("application/json"),
    Text_Plain("text/plain"),
    Text_Html("text/html");

    private final String value;

    ContentType(String value) {
      this.value = value;
    }
  }

  public static ByteBuf unpooledBuffer(String payload) {
    return Unpooled.copiedBuffer(payload.getBytes(StandardCharsets.UTF_8));
  }

  // Request {{{
  public static FullHttpRequest newFullRequest(
      HttpMethod method, String urlPath, ByteBuf buffer, ContentType contentType) {
    FullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, urlPath, buffer);
    request.headers().set(CONTENT_TYPE, contentType.value);
    request.headers().setInt(CONTENT_LENGTH, buffer.readableBytes());
    return request;
  }

  public static HttpRequest newRequestDelete(String urlPath) {
    return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.DELETE, urlPath);
  }

  public static HttpRequest newRequestGet(String urlPath) {
    return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, urlPath);
  }

  public static FullHttpRequest newRequestPost(
      String urlPath, ByteBuf buffer, ContentType contentType) {
    return newFullRequest(HttpMethod.POST, urlPath, buffer, contentType);
  }

  public static FullHttpRequest newRequestPost(
      String urlPath, String payload, ContentType contentType) {
    return newRequestPost(urlPath, unpooledBuffer(payload), contentType);
  }

  public static FullHttpRequest newRequestPut(
      String urlPath, ByteBuf buffer, ContentType contentType) {
    return newFullRequest(HttpMethod.PUT, urlPath, buffer, contentType);
  }

  public static FullHttpRequest newRequestPut(
      String urlPath, String payload, ContentType contentType) {
    return newRequestPut(urlPath, unpooledBuffer(payload), contentType);
  }
  // Request }}}

  // Response {{{
  public static FullHttpResponse newResponse(HttpResponseStatus status) {
    return (FullHttpResponse) new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
  }

  public static FullHttpResponse newResponse(
      HttpResponseStatus status, ByteBuf buffer, ContentType contentType) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buffer);

    response.headers().set(CONTENT_TYPE, contentType.value);
    response.headers().setInt(CONTENT_LENGTH, buffer.readableBytes());

    return response;
  }

  public static FullHttpResponse newResponse(
      HttpResponseStatus status, String payload, ContentType contentType) {
    return newResponse(status, unpooledBuffer(payload), contentType);
  }

  // OK {{{
  public static FullHttpResponse newResponseOk() {
    return newResponse(HttpResponseStatus.OK);
  }

  public static FullHttpResponse newResponseOk(String payload) {
    return newResponse(HttpResponseStatus.OK, payload, ContentType.Text_Plain);
  }

  public static FullHttpResponse newResponseOk(ByteBuf payload, ContentType contentType) {
    return newResponse(HttpResponseStatus.OK, payload, contentType);
  }

  public static FullHttpResponse newResponseOk(String payload, ContentType contentType) {
    return newResponse(HttpResponseStatus.OK, payload, contentType);
  }
  // OK }}}

  // BAD_REQUEST {{{
  public static FullHttpResponse newResponseBadRequest() {
    return newResponse(HttpResponseStatus.BAD_REQUEST);
  }

  public static FullHttpResponse newResponseBadRequest(String payload) {
    return newResponse(HttpResponseStatus.BAD_REQUEST, payload, ContentType.Text_Plain);
  }

  public static FullHttpResponse newResponseBadRequest(String payload, ContentType contentType) {
    return newResponse(HttpResponseStatus.BAD_REQUEST, payload, contentType);
  }
  // BAD_REQUEST }}}
  // Response }}}
}
