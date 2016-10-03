package com.github.kennedyoliveira.hystrix.contrib.vertx.metricsstream;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

/**
 * @author Kennedy Oliveira
 */
@RunWith(VertxUnitRunner.class)
public class EventMetricsStreamTest {

  private Vertx vertx;
  private HttpClient httpClient;

  @Before
  public void setUp(TestContext context) throws Exception {
    vertx = Vertx.vertx();
    httpClient = vertx.createHttpClient(new HttpClientOptions().setDefaultHost("localhost")
                                                               .setDefaultPort(8099));

    EventMetricsStreamHelper.deployStandaloneMetricsStream(vertx, context.asyncAssertSuccess());
  }

  @Test
  public void testFetchData(TestContext context) throws Exception {
    final HystrixCommand<String> dummyCommand = new HystrixCommand<String>(HystrixCommand.Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("testGroup"))
                                                                                                .andCommandKey(HystrixCommandKey.Factory.asKey("testKey"))) {
      @Override
      protected String run() throws Exception {
        return "test";
      }
    };

    dummyCommand.execute();

    final Async request = context.async(10);

    httpClient.getNow("/hystrix.stream", resp -> {
      context.assertEquals(200, resp.statusCode());
      context.assertEquals("text/event-stream;charset=UTF-8", resp.headers().get("Content-Type"));

      resp.handler(buffer -> {
        // received something
        context.assertTrue(buffer.length() > 0);
        System.out.println(buffer.toString(StandardCharsets.UTF_8));
        request.countDown();
      });
    });

    request.await(4000L);
  }

  @After
  public void tearDown(TestContext context) throws Exception {
    if (httpClient != null) {
      httpClient.close();
    }

    vertx.close(context.asyncAssertSuccess());
  }
}
