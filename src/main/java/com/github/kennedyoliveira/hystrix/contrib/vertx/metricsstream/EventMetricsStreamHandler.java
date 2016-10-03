package com.github.kennedyoliveira.hystrix.contrib.vertx.metricsstream;

import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.hystrix.HystrixCollapserMetrics;
import com.netflix.hystrix.HystrixCommandMetrics;
import com.netflix.hystrix.HystrixThreadPoolMetrics;
import com.netflix.hystrix.metric.consumer.HystrixDashboardStream.DashboardData;
import com.netflix.hystrix.serial.SerialHystrixDashboardData;
import io.netty.util.AsciiString;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import io.vertx.rx.java.RxHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Scheduler;
import rx.Subscription;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link Handler} implementing a SSE for Hystrix Metrics.
 *
 * @author Kennedy Oliveira
 * @since 1.5.1
 */
public class EventMetricsStreamHandler implements Handler<RoutingContext> {

  private static final Logger log = LoggerFactory.getLogger(EventMetricsStreamHandler.class);

  /**
   * Default path for metrics stream.
   */
  public static final String DEFAULT_HYSTRIX_PREFIX = "/hystrix.stream";

  /**
   * Default charset.
   */
  private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

  /**
   * Default interval if none was specified.
   */
  private static final int DEFAULT_DELAY = 500;

  /**
   * The payload header to be concatenated before the payload
   */
  private final static Buffer PAYLOAD_HEADER = Buffer.buffer("data: ".getBytes(DEFAULT_CHARSET));

  /**
   * The payload footer, to be concatenated after the payload
   */
  private final static Buffer PAYLOAD_FOOTER = Buffer.buffer(new byte[]{10, 10});

  /**
   * Dynamic Max Concurrent Connections
   */
  private final static DynamicIntProperty maxConcurrentConnections = DynamicPropertyFactory.getInstance().getIntProperty("hystrix.stream.maxConcurrentConnections", 5);

  /**
   * Actual concurrent connections
   */
  private final static AtomicInteger concurrentConnections = new AtomicInteger(0);

  /**
   * Scheduler for interval emission
   */
  private final Scheduler scheduler;

  // Netty optimized headers
  private final static AsciiString HTTP_HEADER_PRAGMA = new AsciiString("Pragma");
  private final static AsciiString HTTP_HEADER_PRAGMA_VALUE = new AsciiString("no-cache");
  private final static AsciiString HTTP_CONTENT_TYPE_VALUE = new AsciiString("text/event-stream;charset=UTF-8");
  private final static AsciiString HTTP_CACHE_CONTROL_VALUE = new AsciiString("no-cache, no-store, max-age=0, must-revalidate");

  private EventMetricsStreamHandler(Vertx vertx) {
    this.scheduler = RxHelper.scheduler(vertx);
  }

  /**
   * Creates a new {@link EventMetricsStreamHandler}
   *
   * @param vertx Vertx instance currently running code
   * @return the new created {@link EventMetricsStreamHandler}
   * @throws NullPointerException if {@code vertx} is null.
   * @since 1.5.1
   */
  public static EventMetricsStreamHandler createHandler(Vertx vertx) {
    Objects.requireNonNull(vertx, "Vertx instance is required.");
    return new EventMetricsStreamHandler(vertx);
  }

  @Override
  public void handle(RoutingContext routingContext) {
    log.debug("[Vertx-EventMetricsStream] - New connection {}:{}", routingContext.request().remoteAddress().host(), routingContext.request().remoteAddress().port());
    final HttpServerRequest request = routingContext.request();
    final HttpServerResponse response = routingContext.response();

    final int currentConnections = concurrentConnections.incrementAndGet();
    final int maxConnections = maxConcurrentConnections.get();
    log.debug("[Vertx-EventMetricsStream] - Current Connections - {} / Max Connections {}", currentConnections, maxConnections);

    if (exceededMaxConcurrentConnections()) {
      response.setStatusCode(503);
      response.end("Max concurrent connections reached: " + maxConnections);
      concurrentConnections.decrementAndGet();
    } else {
      reportMetrics(request, response);
    }
  }

  /**
   * Report the metrics as Event Source Stream.
   *
   * @param request  Request.
   * @param response Response.
   */
  private void reportMetrics(HttpServerRequest request, HttpServerResponse response) {
    response.setChunked(true)
            .setStatusCode(200)
            .headers()
            .add(HttpHeaders.CONTENT_TYPE, HTTP_CONTENT_TYPE_VALUE)
            .add(HttpHeaders.CACHE_CONTROL, HTTP_CACHE_CONTROL_VALUE)
            .add(HTTP_HEADER_PRAGMA, HTTP_HEADER_PRAGMA_VALUE);

    long delay = DEFAULT_DELAY;

    final String requestDelay = request.getParam("delay");
    try {
      if (requestDelay != null && !requestDelay.isEmpty()) {
        delay = Math.max(Long.parseLong(requestDelay), 1);
      }
    } catch (Exception e) {
      log.warn("[Vertx-EventMetricsStream] Error parsing the delay parameter [{}]", requestDelay);
    }

    final Subscription metricsSubscription = Observable.interval(delay, TimeUnit.MILLISECONDS, scheduler)
                                                       .map(i -> new DashboardData(HystrixCommandMetrics.getInstances(),
                                                                                   HystrixThreadPoolMetrics.getInstances(),
                                                                                   HystrixCollapserMetrics.getInstances()))
                                                       .concatMap(dashboardData -> Observable.from(SerialHystrixDashboardData.toMultipleJsonStrings(dashboardData)))
                                                       .onTerminateDetach()
                                                       .subscribe(metric -> writeMetric(metric, response),
                                                                  ex -> log.error("Error sending metrics", ex));

    response.closeHandler(ignored -> {
      log.debug("[Vertx-EventMetricsStream] - Client closed connection, stopping sending metrics");
      metricsSubscription.unsubscribe();
      handleClosedConnection();
    });

    request.exceptionHandler(ig -> {
      log.error("[Vertx-EventMetricsStream] - Sending metrics, stopping sending metrics", ig);
      metricsSubscription.unsubscribe();
      handleClosedConnection();
    });
  }

  /**
   * Handle the connection that has been closed by the client or by some error.
   */
  private void handleClosedConnection() {
    final int currentConnections = concurrentConnections.decrementAndGet();

    log.debug("[Vertx-EventMetricsStream] - Current Connections - {} / Max Connections {}", currentConnections, maxConcurrentConnections.get());
  }

  /**
   * Write the data to the event stream consumer.
   *
   * @param data     Data to be written.
   * @param response Response object to write data to.
   */
  private void writeMetric(String data, HttpServerResponse response) {
    response.write(PAYLOAD_HEADER);
    response.write(Buffer.buffer(data.getBytes(DEFAULT_CHARSET)));
    response.write(PAYLOAD_FOOTER);
  }

  /**
   * Checks if already exceeded the max concurrent connections allowed.
   *
   * @return {@code true} if yes, {@code false} otherwise.
   */
  private boolean exceededMaxConcurrentConnections() {
    return concurrentConnections.get() > maxConcurrentConnections.get();
  }
}
