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

package com.nordstrom.xrpc.server;

import static io.netty.channel.ChannelOption.*;

import com.codahale.metrics.*;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.nordstrom.xrpc.XConfig;
import com.nordstrom.xrpc.logging.ExceptionLogger;
import com.nordstrom.xrpc.server.http.Route;
import com.nordstrom.xrpc.server.http.XHttpMethod;
import com.nordstrom.xrpc.server.tls.Tls;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

@Slf4j
public class Router {
  private final XConfig config;
  private final String workerNameFormat;
  private final int bossThreadCount;
  private final int workerThreadCount;
  private final int MAX_PAYLOAD_SIZE;
  private final MetricRegistry metricRegistry = new MetricRegistry();
  final Slf4jReporter slf4jReporter =
      Slf4jReporter.forRegistry(metricRegistry)
          .outputTo(LoggerFactory.getLogger(Router.class))
          .convertRatesTo(TimeUnit.SECONDS)
          .convertDurationsTo(TimeUnit.MILLISECONDS)
          .build();
  final JmxReporter jmxReporter = JmxReporter.forRegistry(metricRegistry).build();
  private final HealthCheckRegistry healthCheckRegistry = new HealthCheckRegistry(this.workerGroup);
  private final ConsoleReporter consoleReporter =
      ConsoleReporter.forRegistry(metricRegistry)
          .convertRatesTo(TimeUnit.SECONDS)
          .convertDurationsTo(TimeUnit.MILLISECONDS)
          .build();
  private final Tls tls;
  @Getter private Channel channel;
  private EventLoopGroup bossGroup;
  @Getter private EventLoopGroup workerGroup;
  private Class<? extends ServerChannel> channelClass;

  private final XrpcChannelContext ctx;

  public Router(XConfig config) {
    this(config, 1 * 1024 * 1024);
  }

  public Router(XConfig config, int maxPayload) {
    this.config = config;
    this.workerNameFormat = config.workerNameFormat();
    this.bossThreadCount = config.bossThreadCount();
    this.workerThreadCount = config.workerThreadCount();
    this.tls = new Tls(config.cert(), config.key());
    this.MAX_PAYLOAD_SIZE = maxPayload;

    this.ctx = XrpcChannelContext.builder().requestMeter(metricRegistry.meter("requests")).build();

    configResponseCodeMeters();
  }

  private static ThreadFactory threadFactory(String nameFormat) {
    return new ThreadFactoryBuilder().setNameFormat(nameFormat).build();
  }

  private void configResponseCodeMeters() {
    final Map<HttpResponseStatus, String> meterNamesByStatusCode = new ConcurrentHashMap<>(6);

    // Create the proper metrics containers
    final String NAME_PREFIX = "responseCodes.";
    meterNamesByStatusCode.put(HttpResponseStatus.OK, NAME_PREFIX + "ok");
    meterNamesByStatusCode.put(HttpResponseStatus.CREATED, NAME_PREFIX + "created");
    meterNamesByStatusCode.put(HttpResponseStatus.NO_CONTENT, NAME_PREFIX + "noContent");
    meterNamesByStatusCode.put(HttpResponseStatus.BAD_REQUEST, NAME_PREFIX + "badRequest");
    meterNamesByStatusCode.put(HttpResponseStatus.NOT_FOUND, NAME_PREFIX + "notFound");
    meterNamesByStatusCode.put(
        HttpResponseStatus.INTERNAL_SERVER_ERROR, NAME_PREFIX + "serverError");

    for (Map.Entry<HttpResponseStatus, String> entry : meterNamesByStatusCode.entrySet()) {
      ctx.getMetersByStatusCode().put(entry.getKey(), metricRegistry.meter(entry.getValue()));
    }
  }

  public void addHealthCheck(String s, HealthCheck check) {
    healthCheckRegistry.register(s, check);
  }

  public void scheduleHealthChecks() {
    scheduleHealthChecks(60, 60, TimeUnit.SECONDS);
  }

  public void scheduleHealthChecks(int initialDelay, int delay, TimeUnit timeUnit) {
    workerGroup.scheduleWithFixedDelay(
        new Runnable() {
          @Override
          public void run() {
            healthCheckRegistry.runHealthChecks(workerGroup);
          }
        },
        initialDelay,
        delay,
        timeUnit);
  }

  public void addRoute(String s, Handler handler) {
    addRoute(s, handler, XHttpMethod.ANY);
  }

  public void addRoute(String route, Handler handler, HttpMethod method) {
    Preconditions.checkState(method != null);
    Preconditions.checkState(handler != null);

    ImmutableMap<XHttpMethod, Handler> handlerMap =
        new ImmutableMap.Builder<XHttpMethod, Handler>()
            .put(new XHttpMethod(method.name()), handler)
            .build();

    Route _route = Route.build(route);
    Optional<ImmutableSortedMap<Route, List<ImmutableMap<XHttpMethod, Handler>>>> routesOptional =
        Optional.ofNullable(ctx.getRoutes().get());

    if (routesOptional.isPresent()) {

      if (routesOptional.get().containsKey(_route)) {
        ImmutableSortedMap<Route, List<ImmutableMap<XHttpMethod, Handler>>> _routes =
            routesOptional.get();
        _routes.get(_route).add(handlerMap);
        ctx.getRoutes().set(_routes);

      } else {
        ImmutableSortedMap.Builder<Route, List<ImmutableMap<XHttpMethod, Handler>>> routeMap =
            new ImmutableSortedMap.Builder<Route, List<ImmutableMap<XHttpMethod, Handler>>>(
                    Ordering.usingToString())
                .put(Route.build(route), Lists.newArrayList(handlerMap));

        routesOptional.map(value -> routeMap.putAll(value.descendingMap()));

        ctx.getRoutes().set(routeMap.build());
      }
    } else {
      ImmutableSortedMap.Builder<Route, List<ImmutableMap<XHttpMethod, Handler>>> routeMap =
          new ImmutableSortedMap.Builder<Route, List<ImmutableMap<XHttpMethod, Handler>>>(
                  Ordering.usingToString())
              .put(Route.build(route), Lists.newArrayList(handlerMap));

      ctx.getRoutes().set(routeMap.build());
    }
  }

  public AtomicReference<ImmutableSortedMap<Route, List<ImmutableMap<XHttpMethod, Handler>>>>
      getRoutes() {

    return ctx.getRoutes();
  }

  public MetricRegistry getMetricRegistry() {
    return metricRegistry;
  }

  public void serveAdmin() {
    MetricsModule metricsModule = new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true);
    ObjectMapper metricsMapper = new ObjectMapper().registerModule(metricsModule);
    ObjectMapper healthMapper = new ObjectMapper();

    addRoute("/admin", AdminHandlers.adminHandler(), HttpMethod.GET);
    addRoute("/ping", AdminHandlers.pingHandler(), HttpMethod.GET);
    addRoute(
        "/health",
        AdminHandlers.healthCheckHandler(healthCheckRegistry, healthMapper),
        HttpMethod.GET);
    addRoute(
        "/metrics", AdminHandlers.metricsHandler(metricRegistry, metricsMapper), HttpMethod.GET);
  }

  public void listenAndServe() throws IOException {
    ConnectionLimiter globalConnectionLimiter =
        new ConnectionLimiter(
            metricRegistry, config.maxConnections()); // All endpoints for a given service
    ServiceRateLimiter rateLimiter =
        new ServiceRateLimiter(
            metricRegistry,
            config.rateLimit()); // RateLimit incomming connections in terms of req / second

    ServerBootstrap b = new ServerBootstrap();
    UrlRouter router = new UrlRouter(ctx);
    Http2OrHttpHandler h1h2 = new Http2OrHttpHandler(router, ctx);

    if (Epoll.isAvailable()) {
      log.info("Using Epoll");
      bossGroup = new EpollEventLoopGroup(bossThreadCount, threadFactory(workerNameFormat));
      workerGroup = new EpollEventLoopGroup(workerThreadCount, threadFactory(workerNameFormat));
      channelClass = EpollServerSocketChannel.class;
    } else if (KQueue.isAvailable()) {
      log.info("Using KQueue");
      bossGroup = new KQueueEventLoopGroup(bossThreadCount, threadFactory(workerNameFormat));
      workerGroup = new KQueueEventLoopGroup(workerThreadCount, threadFactory(workerNameFormat));
      channelClass = KQueueServerSocketChannel.class;
      b.option(EpollChannelOption.SO_REUSEPORT, true);
    } else {
      log.info("Using NIO");
      bossGroup = new NioEventLoopGroup(bossThreadCount, threadFactory(workerNameFormat));
      workerGroup = new NioEventLoopGroup(workerThreadCount, threadFactory(workerNameFormat));
      channelClass = NioServerSocketChannel.class;
    }

    b.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    b.option(ChannelOption.SO_BACKLOG, 8192);
    b.option(ChannelOption.SO_REUSEADDR, true);

    b.childOption(ChannelOption.SO_REUSEADDR, true);
    b.childOption(SO_KEEPALIVE, true);
    b.childOption(TCP_NODELAY, true);

    b.group(bossGroup, workerGroup);
    b.channel(channelClass);
    b.childHandler(
        new ChannelInitializer<Channel>() {
          @Override
          public void initChannel(Channel ch) throws Exception {
            ChannelPipeline cp = ch.pipeline();
            cp.addLast("serverConnectionLimiter", globalConnectionLimiter);
            cp.addLast("serverRateLimiter", rateLimiter);
            cp.addLast(
                "encryptionHandler", tls.getEncryptionHandler(ch.alloc())); // Add Config for Certs
            //cp.addLast("messageLogger", new MessageLogger()); // TODO(JR): Do not think we need this
            cp.addLast("codec", h1h2);
            cp.addLast(
                "idleDisconnectHandler",
                new IdleDisconnectHandler(
                    config.readerIdleTimeout(),
                    config.writerIdleTimeout(),
                    config.allIdleTimeout()));
            cp.addLast("exceptionLogger", new ExceptionLogger());
          }
        });

    ChannelFuture future = b.bind(new InetSocketAddress(config.port()));

    try {
      // Get some loggy logs
      consoleReporter.start(30, TimeUnit.SECONDS);
      // This is too noisy right now, re-enable prior to shipping.
      //slf4jReporter.start(30, TimeUnit.SECONDS);
      jmxReporter.start();

      future.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted waiting for bind");
    }

    if (!future.isSuccess()) {
      throw new IOException("Failed to bind", future.cause());
    }

    channel = future.channel();
  }

  public void shutdown() {
    if (channel == null || !channel.isOpen()) {
      return;
    }

    channel
        .close()
        .addListener(
            new ChannelFutureListener() {
              @Override
              public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                  log.warn("Error shutting down server", future.cause());
                }
                synchronized (Router.this) {
                  // listener.serverShutdown();
                }
              }
            });
  }

  @ChannelHandler.Sharable
  class NoOpHandler extends ChannelDuplexHandler {

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      ctx.pipeline().remove(this);
      ctx.fireChannelActive();
    }
  }

  class IdleDisconnectHandler extends IdleStateHandler {

    public IdleDisconnectHandler(
        int readerIdleTimeSeconds, int writerIdleTimeSeconds, int allIdleTimeSeconds) {
      super(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
      ctx.channel().close();
    }
  }
}
