package com.nordstrom.xrpc.server;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;

import com.google.common.collect.ImmutableMap;
import com.nordstrom.xrpc.server.http.Route;
import com.nordstrom.xrpc.server.http.XHttpMethod;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http2.*;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class Http2Handler extends Http2ConnectionHandler implements Http2FrameListener {

  private final XrpcChannelContext xctx;
  private XrpcRequest xrpcRequest;

  Http2Handler(
      XrpcChannelContext xctx,
      Http2ConnectionDecoder decoder,
      Http2ConnectionEncoder encoder,
      Http2Settings initialSettings) {
    super(decoder, encoder, initialSettings);
    this.xctx = xctx;
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {}

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    super.exceptionCaught(ctx, cause);
    cause.printStackTrace();
    ctx.close();
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    xctx.getRequestMeter().mark();
  }

  private void executeHandler(ChannelHandlerContext ctx, int streamId, Route route)
      throws IOException {
    FullHttpResponse h1Resp;
    Optional<ImmutableMap<XHttpMethod, Handler>> handlerMapOptional =
        xctx.getRoutes()
            .get()
            .get(route)
            .stream()
            .filter(
                m ->
                    m.keySet()
                        .stream()
                        .anyMatch(
                            mx ->
                                mx.compareTo(
                                        XHttpMethod.valueOf(
                                            xrpcRequest.getH2Headers().method().toString()))
                                    == 0))
            .findFirst();

    if (handlerMapOptional.isPresent()) {
      h1Resp =
          (FullHttpResponse)
              handlerMapOptional
                  .get()
                  .get(handlerMapOptional.get().keySet().asList().get(0))
                  .handle(xrpcRequest);
    } else {
      h1Resp =
          (FullHttpResponse)
              xctx.getRoutes()
                  .get()
                  .get(route)
                  .stream()
                  .filter(mx -> mx.containsKey(XHttpMethod.ANY))
                  .findFirst()
                  .get()
                  .get(XHttpMethod.ANY)
                  .handle(xrpcRequest);
    }

    xctx.getMetersByStatusCode().get(h1Resp.status()).mark();

    Http2Headers responseHeaders = HttpConversionUtil.toHttp2Headers(h1Resp, true);
    Http2DataFrame responseDataFrame = new DefaultHttp2DataFrame(h1Resp.content(), true);
    encoder().writeHeaders(ctx, streamId, responseHeaders, 0, false, ctx.newPromise());
    encoder().writeData(ctx, streamId, responseDataFrame.content(), 0, true, ctx.newPromise());
  }

  private void writeResponse(
      ChannelHandlerContext ctx, int streamId, HttpResponseStatus status, ByteBuf buffer) {
    FullHttpResponse h1Resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buffer);
    h1Resp.headers().set(CONTENT_TYPE, "text/plain");
    h1Resp.headers().setInt(CONTENT_LENGTH, buffer.readableBytes());

    Http2Headers responseHeaders = HttpConversionUtil.toHttp2Headers(h1Resp, true);
    Http2DataFrame responseDataFrame = new DefaultHttp2DataFrame(h1Resp.content(), true);
    encoder().writeHeaders(ctx, streamId, responseHeaders, 0, false, ctx.newPromise());
    encoder().writeData(ctx, streamId, responseDataFrame.content(), 0, true, ctx.newPromise());

    xctx.getMetersByStatusCode().get(status).mark();
  }

  @Override
  public int onDataRead(
      ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
    int processed = data.readableBytes() + padding;

    if (endOfStream) {
      xrpcRequest.setData(data);
      for (Route route : xctx.getRoutes().get().descendingKeySet()) {
        Optional<Map<String, String>> groups =
            Optional.ofNullable(route.groups(xrpcRequest.getH2Headers().path().toString()));
        if (groups.isPresent()) {
          try {
            executeHandler(ctx, streamId, route);
          } catch (IOException e) {
            log.error("Error in handling Route", e);
            // Error
            ByteBuf buf = ctx.channel().alloc().directBuffer();
            buf.writeBytes("Error executing endpoint".getBytes());
            writeResponse(ctx, streamId, HttpResponseStatus.INTERNAL_SERVER_ERROR, buf);
          }
        }
      }
    }
    return processed;
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
      Http2Headers headers,
      int padding,
      boolean endOfStream) {

    String uri = headers.path().toString();
    for (Route route : xctx.getRoutes().get().descendingKeySet()) {
      Optional<Map<String, String>> groups = Optional.ofNullable(route.groups(uri));
      if (groups.isPresent()) {
        xrpcRequest = new XrpcRequest(headers, groups.get(), ctx.channel(), streamId);
        Optional<CharSequence> contentLength = Optional.ofNullable(headers.get("content-length"));
        if (!contentLength.isPresent()) {
          try {
            executeHandler(ctx, streamId, route);
            return;
          } catch (IOException e) {
            log.error("Error in handling Route", e);
            // Error
            ByteBuf buf = ctx.channel().alloc().directBuffer();
            buf.writeBytes("Error executing endpoint".getBytes());
            writeResponse(ctx, streamId, HttpResponseStatus.INTERNAL_SERVER_ERROR, buf);
          }
        }
      }
    }
    // No Valid Route
    ByteBuf buf = ctx.channel().alloc().directBuffer();
    buf.writeBytes("Endpoint not found".getBytes());
    writeResponse(ctx, streamId, HttpResponseStatus.NOT_FOUND, buf);
  }

  @Override
  public void onHeadersRead(
      ChannelHandlerContext ctx,
      int streamId,
      Http2Headers headers,
      int streamDependency,
      short weight,
      boolean exclusive,
      int padding,
      boolean endOfStream) {
    onHeadersRead(ctx, streamId, headers, padding, endOfStream);
  }

  @Override
  public void onPriorityRead(
      ChannelHandlerContext ctx,
      int streamId,
      int streamDependency,
      short weight,
      boolean exclusive) {}

  @Override
  public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {}

  @Override
  public void onSettingsAckRead(ChannelHandlerContext ctx) {}

  @Override
  public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {}

  @Override
  public void onPingRead(ChannelHandlerContext ctx, ByteBuf data) {}

  @Override
  public void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data) {}

  @Override
  public void onPushPromiseRead(
      ChannelHandlerContext ctx,
      int streamId,
      int promisedStreamId,
      Http2Headers headers,
      int padding) {}

  @Override
  public void onGoAwayRead(
      ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData) {}

  @Override
  public void onWindowUpdateRead(
      ChannelHandlerContext ctx, int streamId, int windowSizeIncrement) {}

  @Override
  public void onUnknownFrame(
      ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload) {}
}
