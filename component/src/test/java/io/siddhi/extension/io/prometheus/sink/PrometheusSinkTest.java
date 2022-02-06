/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.siddhi.extension.io.prometheus.sink;

import io.siddhi.core.SiddhiAppRuntime;
import io.siddhi.core.SiddhiManager;
import io.siddhi.core.event.Event;
import io.siddhi.core.exception.CannotRestoreSiddhiAppStateException;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.stream.input.InputHandler;
import io.siddhi.core.stream.output.StreamCallback;
import io.siddhi.core.util.SiddhiTestHelper;
import io.siddhi.core.util.config.InMemoryConfigManager;
import io.siddhi.core.util.persistence.InMemoryPersistenceStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tapestry5.json.JSONArray;
import org.apache.tapestry5.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test cases for prometheus sink in server and pushgateway publish modes.
 * Prometheus server and pushgateway must be up and running for the testcases to pass.
 * Targets must be configured inside the Prometheus configuration file (prometheus.yml) as,
 * - job_name: 'server'
 * honor_labels: true
 * static_configs:
 * - targets: ['localhost:9080']
 * <p>
 * - job_name: 'pushgateway'
 * honor_labels: true
 * static_configs:
 * - targets: ['localhost:9091']
 * <p>
 * - job_name: 'configurationTest'
 * honor_labels: true
 * static_configs:
 * - targets: ['localhost:9096']
 */
public class PrometheusSinkTest {

    private static final Logger log = LogManager.getLogger(PrometheusSinkTest.class);
    private static String pushgatewayURL;
    private static String serverURL;
    private static ExecutorService executorService;
    private AtomicInteger eventCount = new AtomicInteger(0);
    private AtomicBoolean eventArrived = new AtomicBoolean(false);
    private List<Object[]> createdEvents = new ArrayList<>();
    private static String prometheusServerURL;


    @BeforeClass
    public static void startTest() {
        String prometheusPort = System.getenv("PROMETHEUS_PORT");
        String pushPort = System.getenv("PUSHGATEWAY_PORT");
        String serverPort = System.getenv("SERVER_PORT");
        String host = System.getenv("HOST_NAME");
        pushgatewayURL = "http://" + host + ":" + pushPort;
        serverURL = "http://" + host + ":" + serverPort;
        prometheusServerURL = "http://" + host + ":" + prometheusPort + "/api/v1/query?query=";
        executorService = Executors.newFixedThreadPool(5);
        log.info("== Prometheus sink tests started ==");
    }

    @AfterClass
    public static void shutdown() throws InterruptedException {
        while (!executorService.isShutdown() || !executorService.isTerminated()) {
            executorService.shutdown();
        }
        Thread.sleep(100);
        log.info("== Prometheus sink tests completed ==");
    }

    @BeforeMethod
    public void beforeTest() {
        eventCount.set(0);
        eventArrived.set(false);
        createdEvents.clear();
    }

    private void getAndValidateMetrics(String metricName) {

        String requestURL = prometheusServerURL + metricName;
        try {
            URL url = new URL(requestURL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            StringBuilder response = new StringBuilder();
            conn.connect();

            if (conn.getResponseCode() != 200) {
                Assert.fail("Http error: " + conn.getResponseCode() + "\n" + conn.getResponseMessage());
            } else {
                String inputLine;
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), Charset.defaultCharset()));
                while ((inputLine = reader.readLine()) != null) {
                    response.append(inputLine);
                }
                reader.close();
                JSONObject queryResult = new JSONObject(response.toString());
                JSONArray results = queryResult.getJSONObject("data").getJSONArray("result");
                for (int i = results.length() - 1; i >= 0; i--) {
                    Object symbol = results.getJSONObject(i).getJSONObject("metric").get("symbol");
                    Object price = results.getJSONObject(i).getJSONObject("metric").get("price");
                    Object value = results.getJSONObject(i).getJSONArray("value").get(1);

                    createdEvents.add(new Object[]{symbol, Integer.parseInt(value.toString()),
                            Double.parseDouble(price.toString())});
                }
            }
            conn.disconnect();

        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }


    /**
     * test for Prometheus sink with keyvalue mapping.
     *
     * @throws InterruptedException interrupted exception
     */
    @Test
    public void prometheusSinkTest() throws InterruptedException {

        SiddhiManager siddhiManager = new SiddhiManager();
        log.info("----------------------------------------------------------------------------------");
        log.info("Prometheus Sink test with mandatory fields");
        log.info("----------------------------------------------------------------------------------");
        String inputStream = "@App:name('TestSiddhiApp')" +
                "                        \"define stream InputStream (symbol String, value int, price double);";
        String sinkStream = "@sink(type='prometheus'," +
                "job='sinkTest'," +
                "publish.mode='server'," +
                "push.url='" + serverURL + "'," +
                "metric.type='counter'," +
                "@map(type = 'keyvalue'))" +
                "Define stream SinkMapTestStream (symbol String, value int, price double);";
        String query = (
                "@info(name = 'query') "
                        + "from InputStream "
                        + "select *"
                        + "insert into SinkMapTestStream;"
        );
        StreamCallback streamCallback = new StreamCallback() {
            @Override
            public void receive(Event[] events) {
                for (Event event : events) {
                    eventCount.getAndIncrement();
                    eventArrived.set(true);
                }
            }
        };

        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(inputStream + sinkStream + query);
        siddhiAppRuntime.addCallback("SinkMapTestStream", streamCallback);

        siddhiAppRuntime.start();

        List<Object[]> inputEvents = new ArrayList<>();
        InputHandler inputHandler = siddhiAppRuntime.getInputHandler("InputStream");
        Object[] inputEvent1 = new Object[]{"WSO2", 100, 78.8};
        Object[] inputEvent2 = new Object[]{"IBM", 125, 65.32};
        inputHandler.send(inputEvent1);
        inputHandler.send(inputEvent2);
        inputEvents.add(inputEvent1);
        inputEvents.add(inputEvent2);
        Thread.sleep(10000);

        getAndValidateMetrics("SinkMapTestStream");

        Assert.assertTrue(eventArrived.get());
        Assert.assertEquals(eventCount.get(), 2);
        Assert.assertTrue(SiddhiTestHelper.isEventsMatch(inputEvents, createdEvents));
        siddhiAppRuntime.shutdown();
    }

    /**
     * test for Prometheus sink in server publish mode.
     *
     * @throws Exception Interrupted exception
     */
    @Test(dependsOnMethods = "prometheusSinkTest")
    public void prometheusSinkTestServerMode() throws InterruptedException {

        log.info("----------------------------------------------------------------------------------");
        log.info("Prometheus Sink test with server mode");
        log.info("----------------------------------------------------------------------------------");

        SiddhiManager siddhiManager = new SiddhiManager();

        String streamDefinition = "" +
                "define stream InputStream (symbol String, volume int, price double);" +
                "@sink(type='prometheus'," +
                "job='prometheusSinkTest'," +
                "server.url='" + serverURL + "'," +
                "publish.mode='server'," +
                "metric.type='gauge'," +
                "metric.help= 'Server mode test'," +
                "metric.name= 'testing_metrics'," +
                "value.attribute= 'volume', " +
                "@map(type = \'keyvalue\'))"
                + "Define stream TestStream (symbol String, volume int, price double);";
        String query = (
                "@info(name = 'query') "
                        + "from InputStream "
                        + "select symbol, volume, price "
                        + "insert into TestStream;"
        );

        StreamCallback streamCallback = new StreamCallback() {
            @Override
            public void receive(Event[] events) {
                for (Event event : events) {
                    eventCount.getAndIncrement();
                    eventArrived.set(true);
                }
            }
        };

        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(streamDefinition + query);
        InputHandler inputHandler = siddhiAppRuntime.getInputHandler("InputStream");
        siddhiAppRuntime.addCallback("TestStream", streamCallback);
        siddhiAppRuntime.start();
        List<Object[]> inputEvents = new ArrayList<>();
        Object[] inputEvent1 = new Object[]{"WSO2", 100, 78.8};
        Object[] inputEvent2 = new Object[]{"IBM", 125, 65.32};
        inputHandler.send(inputEvent1);
        inputHandler.send(inputEvent2);
        inputEvents.add(inputEvent1);
        inputEvents.add(inputEvent2);

        Thread.sleep(10000);

        getAndValidateMetrics("testing_metrics");

        Assert.assertTrue(eventArrived.get());
        Assert.assertEquals(eventCount.get(), 2);
        Assert.assertTrue(SiddhiTestHelper.isEventsMatch(inputEvents, createdEvents));

        siddhiAppRuntime.shutdown();
    }

    /**
     * test for Prometheus sink with value attribute configuration.
     *
     * @throws Exception Interrupted exception
     */
    @Test(enabled = false, dependsOnMethods = "prometheusSinkTest")
    public void prometheusSinkTestPushgatewayMode() throws InterruptedException {
        log.info("----------------------------------------------------------------------------------");
        log.info("Prometheus Sink test with pushgateway mode");
        log.info("----------------------------------------------------------------------------------");

        SiddhiManager siddhiManager = new SiddhiManager();

        String streamDefinition = "" +
                "define stream InputStream (symbol String, volume int, price double);" +
                "@sink(type='prometheus'," +
                "job='prometheusSinkTest'," +
                "push.url='" + pushgatewayURL + "'," +
                "publish.mode='pushgateway'," +
                "metric.type='gauge'," +
                "metric.help= 'Pushgateway mode test'," +
                "metric.name= 'test_metrics'," +
                "value.attribute= 'volume'," +
                "push.operation = 'push' ," +
                "grouping.key= \"'subjob:pushTest','purpose:testing'\"," +
                "@map(type = \'keyvalue\'))"
                + "Define stream TestStream (symbol String, volume int, price double);";
        String query = (
                "@info(name = 'query') "
                        + "from InputStream "
                        + "select symbol, volume, price "
                        + "insert into TestStream;"
        );

        StreamCallback streamCallback = new StreamCallback() {
            @Override
            public void receive(Event[] events) {
                for (Event event : events) {
                    eventCount.getAndIncrement();
                    eventArrived.set(true);
                }
            }
        };

        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(streamDefinition + query);
        InputHandler inputHandler = siddhiAppRuntime.getInputHandler("InputStream");
        siddhiAppRuntime.addCallback("TestStream", streamCallback);
        siddhiAppRuntime.start();
        List<Object[]> inputEvents = new ArrayList<>();
        Object[] inputEvent1 = new Object[]{"WSO2", 100, 78.8};
        Object[] inputEvent2 = new Object[]{"IBM", 125, 65.32};
        inputHandler.send(inputEvent1);
        inputHandler.send(inputEvent2);
        inputEvents.add(inputEvent1);
        inputEvents.add(inputEvent2);

        Thread.sleep(10000);
        getAndValidateMetrics("test_metrics");

        Assert.assertTrue(eventArrived.get());
        Assert.assertEquals(eventCount.get(), 2);
        Assert.assertTrue(SiddhiTestHelper.isEventsMatch(inputEvents, createdEvents));

        siddhiAppRuntime.shutdown();
    }

    @Test(dependsOnMethods = "prometheusSinkTest")
    public void prometheusConnectionTestMultipleSink() throws Exception {

        log.info("----------------------------------------------------------------------------------");
        log.info("Test to verify Prometheus sink with multiple sink definitions in server mode.");
        log.info("----------------------------------------------------------------------------------");

        SiddhiManager siddhiManager = new SiddhiManager();

        String inputStream = "define stream InputStream (symbol String, value int, price double);";
        String sinkStream1 = "@sink(type='prometheus'," +
                "job='Test'," +
                "publish.mode='server'," +
                "server.url='" + serverURL + "'," +
                "metric.type='counter'," +
                "@map(type = \'keyvalue\'))" +
                "Define stream TestStream1 (symbol String, value int, price double);";
        String sinkStream2 = "@sink(type='prometheus'," +
                "job='Test'," +
                "publish.mode='server'," +
                "server.url='" + serverURL + "'," +
                "metric.type='gauge'," +
                "@map(type = \'keyvalue\'))" +
                "Define stream TestStream2 (symbol String, value int, price double);";
        String query1 = (
                "@info(name = 'query1') "
                        + "from InputStream "
                        + "select symbol, value, price "
                        + "insert into TestStream1;"
        );
        String query2 = (
                "@info(name = 'query2') "
                        + "from InputStream "
                        + "select symbol, value, price "
                        + "insert into TestStream2;"
        );
        StreamCallback streamCallback = new StreamCallback() {
            @Override
            public void receive(Event[] events) {
                for (Event event : events) {
                    eventCount.getAndIncrement();
                    eventArrived.set(true);
                }
            }
        };
        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(inputStream + sinkStream1 +
                sinkStream2 + query1 + query2);
        siddhiAppRuntime.addCallback("TestStream1", streamCallback);
        siddhiAppRuntime.addCallback("TestStream2", streamCallback);
        InputHandler inputHandler = siddhiAppRuntime.getInputHandler("InputStream");
        siddhiAppRuntime.start();
        List<Object[]> inputEvents = new ArrayList<>();
        Object[] inputEvent1 = new Object[]{"WSO2", 100, 78.8};
        Object[] inputEvent2 = new Object[]{"IBM", 125, 65.32};
        inputHandler.send(inputEvent1);
        inputHandler.send(inputEvent2);
        inputEvents.add(inputEvent1);
        inputEvents.add(inputEvent2);
        Thread.sleep(10000);

        getAndValidateMetrics("TestStream1");

        Assert.assertTrue(eventArrived.get());
        Assert.assertEquals(eventCount.get(), 4);
        Assert.assertTrue(SiddhiTestHelper.isEventsMatch(inputEvents, createdEvents));

        createdEvents.clear();

        getAndValidateMetrics("TestStream2");
        Assert.assertEquals(eventCount.get(), 4);
        Assert.assertTrue(SiddhiTestHelper.isEventsMatch(inputEvents, createdEvents));

        siddhiAppRuntime.shutdown();
    }

    @Test(dependsOnMethods = "prometheusSinkTestServerMode")
    public void prometheusConfigurationTest() throws Exception {

        log.info("----------------------------------------------------------------------------------");
        log.info("Test to verify Prometheus sink with custom configuration.");
        log.info("----------------------------------------------------------------------------------");

        String serverPort = System.getenv("SERVER_CONFIG_PORT");
        String host = System.getenv("HOST_NAME");
        String url = "http://" + host + ":" + serverPort;
        Map<String, String> serverConfig = new HashMap<>();
        serverConfig.put("sink.prometheus.publishMode", "server");
        serverConfig.put("sink.prometheus.serverURL", url);
        serverConfig.put("sink.prometheus.groupingKey", "'name:company','product:APIM'");
        InMemoryConfigManager configManager = new InMemoryConfigManager(null, serverConfig);
        configManager.generateConfigReader("sink", "prometheus");

        SiddhiManager siddhiManager = new SiddhiManager();
        siddhiManager.setConfigManager(configManager);

        String streamDefinition = "" +
                "define stream InputStream (symbol String, volume int, price double);" +
                "@sink(type='prometheus'," +
                "metric.type='gauge'," +
                "metric.help= 'configuration test'," +
                "metric.name= 'metric_test'," +
                "value.attribute= 'volume'," +
                "@map(type = \'keyvalue\'))"
                + "Define stream TestStream (symbol String, volume int, price double);";
        String query = (
                "@info(name = 'query') "
                        + "from InputStream "
                        + "select symbol, volume, price "
                        + "insert into TestStream;"
        );

        StreamCallback streamCallback = new StreamCallback() {
            @Override
            public void receive(Event[] events) {
                for (Event event : events) {
                    eventCount.getAndIncrement();
                    eventArrived.set(true);
                }
            }
        };

        SiddhiAppRuntime siddhiAppRuntime = siddhiManager.createSiddhiAppRuntime(streamDefinition + query);
        InputHandler inputHandler = siddhiAppRuntime.getInputHandler("InputStream");
        siddhiAppRuntime.addCallback("TestStream", streamCallback);
        siddhiAppRuntime.start();
        List<Object[]> inputEvents = new ArrayList<>();
        Object[] inputEvent1 = new Object[]{"WSO2", 100, 78.8};
        Object[] inputEvent2 = new Object[]{"IBM", 125, 65.32};
        inputHandler.send(inputEvent1);
        inputHandler.send(inputEvent2);
        inputEvents.add(inputEvent1);
        inputEvents.add(inputEvent2);
        Thread.sleep(10000);

        getAndValidateMetrics("metric_test");

        Assert.assertTrue(eventArrived.get());
        Assert.assertEquals(eventCount.get(), 2);
        Assert.assertTrue(SiddhiTestHelper.isEventsMatch(inputEvents, createdEvents));

        siddhiAppRuntime.shutdown();
    }

    /**
     * test for Prometheus sink in persist and restore state.
     *
     * @throws InterruptedException
     **/
    @Test(dependsOnMethods = "prometheusConfigurationTest")
    public void prometheusSinkTestNodeFailure() throws InterruptedException, CannotRestoreSiddhiAppStateException {

        log.info("----------------------------------------------------------------------------------");
        log.info("Test to verify recovering of the Siddhi node on a failure ");
        log.info("----------------------------------------------------------------------------------");

        SiddhiManager siddhiManager = new SiddhiManager();
        siddhiManager.setPersistenceStore(new InMemoryPersistenceStore());

        final String sinkRecoveryQuery = "@App:name('prometheusRecoveryApp') " +
                "@sink(type='prometheus',job='prometheusSinkTest'," +
                "server.url='" + serverURL + "', publish.mode='server', metric.type='histogram', " +
                "metric.help= 'Metric definition test', metric.name= 'recovery_test', " +
                " @map(type = \'keyvalue\'))" +
                "define stream SinkTestStream (symbol String, value double, price double);" +

                "Define stream FooStream (symbol string,value double, price double); " +

                "from FooStream select symbol, value, price insert into SinkTestStream;";

        SiddhiAppRuntime prometheusRecoveryApp = siddhiManager.createSiddhiAppRuntime(sinkRecoveryQuery);
        InputHandler fooStream = prometheusRecoveryApp.getInputHandler("FooStream");
        prometheusRecoveryApp.addCallback("SinkTestStream", new StreamCallback() {
            @Override
            public synchronized void receive(Event[] events) {
                for (Event event : events) {
                    eventCount.getAndIncrement();
                    eventArrived.set(true);
                }
            }
        });
        // Start the apps
        prometheusRecoveryApp.start();
        // start publishing events
        Future eventSender = executorService.submit(() -> {

            try {
                fooStream.send(new Object[]{"WSO2", 40, 98.78});
                fooStream.send(new Object[]{"IBM", 70, 78.09});
                fooStream.send(new Object[]{"WSO2", 35, 98.78});
                fooStream.send(new Object[]{"APPLE", 25, 32.56});
                fooStream.send(new Object[]{"WSO2", 85, 98.78});
            } catch (InterruptedException e) {
                throw new SiddhiAppCreationException("Sending interrupted " + e);
            }
        });

        while (!eventSender.isDone()) {
            Thread.sleep(100);
        }
        while (!prometheusRecoveryApp.persist().getFuture().isDone()) {
            Thread.sleep(100);
        }
        log.info("Finished publishing 5 events to the stream.");

        // Send more events after persisting the state
        eventSender = executorService.submit(() ->
        {
            try {
                fooStream.send(new Object[]{"IBM", 40, 78.09});
                fooStream.send(new Object[]{"IBM", 70, 78.09});
                fooStream.send(new Object[]{"APPLE", 35, 32.56});
                fooStream.send(new Object[]{"WSO2", 55, 98.78});
                fooStream.send(new Object[]{"APPLE", 85, 32.56});
            } catch (InterruptedException e) {
                throw new SiddhiAppCreationException("Sending interrupted " + e);
            }
        });
        while (!eventSender.isDone()) {
            Thread.sleep(100);
        }
        // Shutting down the app to pretend a node failure and starting it again like a restart
        prometheusRecoveryApp.shutdown();
        log.info("Restarting the external Siddhi App to mimic a node failure and a restart");
        prometheusRecoveryApp = siddhiManager.createSiddhiAppRuntime(sinkRecoveryQuery);
        prometheusRecoveryApp.start();

        // Restore the state from last snapshot that was taken before shutdown
        prometheusRecoveryApp.restoreLastRevision();
        Assert.assertEquals(eventCount.get(), 10);
        prometheusRecoveryApp.shutdown();
        Thread.sleep(100);
    }
}
