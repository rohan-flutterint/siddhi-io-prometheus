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

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.exporter.PushGateway;
import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.SystemParameter;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiAppContext;
import io.siddhi.core.exception.ConnectionUnavailableException;
import io.siddhi.core.exception.SiddhiAppCreationException;
import io.siddhi.core.stream.ServiceDeploymentInfo;
import io.siddhi.core.stream.output.sink.Sink;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.snapshot.state.State;
import io.siddhi.core.util.snapshot.state.StateFactory;
import io.siddhi.core.util.transport.DynamicOptions;
import io.siddhi.core.util.transport.OptionHolder;
import io.siddhi.extension.io.prometheus.sink.util.PrometheusMetricBuilder;
import io.siddhi.extension.io.prometheus.util.PrometheusConstants;
import io.siddhi.extension.io.prometheus.util.PrometheusSinkUtil;
import io.siddhi.query.api.annotation.Annotation;
import io.siddhi.query.api.definition.Attribute;
import io.siddhi.query.api.definition.StreamDefinition;
import io.siddhi.query.api.exception.AttributeNotExistException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.DEFAULT_ERROR;
import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.EMPTY_STRING;
import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.HELP_STRING;
import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.METRIC_TYPE;
import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.PUSHGATEWAY_PUBLISH_MODE;
import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.PUSH_ADD_OPERATION;
import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.PUSH_OPERATION;
import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.SERVER_PUBLISH_MODE;
import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.SPACE_STRING;
import static io.siddhi.extension.io.prometheus.util.PrometheusConstants.VALUE_STRING;
import static java.lang.Double.parseDouble;

/**
 * Extension for Siddhi to publish events as Prometheus metrics.
 **/
@Extension(
        name = "prometheus",
        namespace = "sink",
        description = "This sink publishes events processed by Siddhi into Prometheus metrics and exposes " +
                "them to the Prometheus server at the specified URL. The created metrics can be published to " +
                "Prometheus via 'server' or 'pushGateway', depending on your preference.\n " +
                "The metric types that are supported by the Prometheus sink are 'counter', 'gauge', 'histogram', " +
                "and 'summary'. The values and labels of the Prometheus metrics can be updated through the events. ",
        parameters = {
                @Parameter(
                        name = "job",
                        description = "This parameter specifies the job name of the metric. This must be " +
                                "the same job name that is defined in the Prometheus configuration file.",
                        defaultValue = "siddhiJob",
                        optional = true,
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "publish.mode",
                        description = "The mode in which the metrics need to be exposed to the Prometheus server." +
                                "The possible publishing modes are \'server\' and \'pushgateway\'.The server mode " +
                                "exposes the metrics through an HTTP server at the specified URL, and the " +
                                "'pushGateway' mode pushes the metrics to the pushGateway that needs to be running at" +
                                " the specified URL.",
                        defaultValue = "server",
                        optional = true,
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "push.url",
                        description = "This parameter specifies the target URL of the Prometheus pushGateway. This " +
                                "is the URL at which the pushGateway must be listening. This URL needs to be " +
                                "defined in the Prometheus configuration file as a target before it can be used here.",
                        optional = true,
                        defaultValue = "http://localhost:9091",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "server.url",
                        description = "This parameter specifies the URL where the HTTP server is initiated " +
                                "to expose metrics in the \'server\' publish mode. This URL needs to be defined in" +
                                " the Prometheus configuration file as a target before it can be used here.",
                        optional = true,
                        defaultValue = "http://localhost:9080",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "metric.type",
                        description = "The type of Prometheus metric that needs to be created at the sink.\n " +
                                "The supported metric types are \'counter\', \'gauge\',c\'histogram\' and " +
                                "\'summary\'. ",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "metric.help",
                        description = "A brief description of the metric and its purpose.",
                        optional = true,
                        defaultValue = "<metric_name_with_metric_type>",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "metric.name",
                        description = "This parameter allows you to assign a preferred name for the metric." +
                                " The metric name must match the regex format, i.e., [a-zA-Z_:][a-zA-Z0-9_:]*. ",
                        optional = true,
                        defaultValue = "<stream_name>",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "buckets",
                        description = "The bucket values preferred by the user for histogram metrics. The bucket " +
                                "values must be in the 'string' format with each bucket value separated by a comma " +
                                "as shown in the example below.\n" +
                                "\"2,4,6,8\"",
                        optional = true,
                        defaultValue = "null",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "quantiles",
                        description = "This parameter allows you to specify quantile values for summary metrics " +
                                "as preferred. The quantile values must be in the 'string' format with each " +
                                "quantile value separated by a comma as shown in the example below.\n" +
                                "\"0.5,0.75,0.95\"",
                        optional = true,
                        defaultValue = "null",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "quantile.error",
                        description = "The error tolerance value for calculating quantiles in summary metrics. " +
                                "This must be a positive value, but less than 1.",
                        optional = true,
                        defaultValue = "0.001",
                        type = {DataType.DOUBLE}
                ),
                @Parameter(
                        name = "value.attribute",
                        description = "The name of the attribute in the stream definition that specifies the metric " +
                                "value. The defined 'value' attribute must be included in the stream definition." +
                                " The system increases the metric value for the counter and gauge metric types by " +
                                "the value of the 'value attribute. The system observes the value of the 'value' " +
                                "attribute for the calculations of 'summary' and 'histogram' metric types.",
                        optional = true,
                        defaultValue = "value",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "push.operation",
                        description = "This parameter defines the mode for pushing metrics to the pushGateway. " +
                                "The available push operations are \'push\' and \'pushadd\'. " +
                                "The operations differ according to the existing metrics in pushGateway where " +
                                "\'push\' operation replaces the existing metrics, and \'pushadd\' operation " +
                                "only updates the newly created metrics.",
                        optional = true,
                        defaultValue = "pushadd",
                        type = {DataType.STRING}
                ),
                @Parameter(
                        name = "grouping.key",
                        description = "This parameter specifies the grouping key of created metrics in key-value " +
                                "pairs. The grouping key is used only in pushGateway mode in order to distinguish " +
                                "the metrics from already existing metrics. \nThe expected format of the grouping key" +
                                " is as follows:\n " +
                                "\"'key1:value1','key2:value2'\"",
                        optional = true,
                        defaultValue = "<empty_string>",
                        type = {DataType.STRING}
                )
        },
        examples = {
                @Example(
                        syntax =
                                "@sink(type='prometheus',job='fooOrderCount', server.url ='http://localhost:9080', " +
                                        "publish.mode='server', metric.type='counter', " +
                                        "metric.help= 'Number of foo orders', @map(type='keyvalue'))\n" +
                                        "define stream FooCountStream (Name String, quantity int, value int);\n",
                        description = " In the above example, the Prometheus-sink creates a counter metric " +
                                "with the stream name and defined attributes as labels. The metric is exposed" +
                                " through an HTTP server at the target URL."
                ),
                @Example(
                        syntax =
                                "@sink(type='prometheus',job='inventoryLevel', push.url='http://localhost:9080', " +
                                        "publish.mode='pushGateway', metric.type='gauge'," +
                                        " metric.help= 'Current level of inventory', @map(type='keyvalue'))\n" +
                                        "define stream InventoryLevelStream (Name String, value int);\n",
                        description = " In the above example, the Prometheus-sink creates a gauge metric " +
                                "with the stream name and defined attributes as labels." +
                                "The metric is pushed to the Prometheus pushGateway at the target URL."
                )
        },
        systemParameter = {
                @SystemParameter(
                        name = "jobName",
                        description = "This property specifies the default job name for the metric. " +
                                "This job name must be the same as the job name defined in the Prometheus " +
                                "configuration file.",
                        defaultValue = "siddhiJob",
                        possibleParameters = "Any string"
                ),
                @SystemParameter(
                        name = "publishMode",
                        description = "The default publish mode for the Prometheus sink for exposing metrics to the" +
                                " Prometheus server. The mode can be either \'server\' or \'pushgateway\'. ",
                        defaultValue = "server",
                        possibleParameters = "server or pushgateway"
                ),
                @SystemParameter(
                        name = "serverURL",
                        description = "This property configures the URL where the HTTP server is initiated " +
                                "to expose metrics. This URL needs to be defined in the Prometheus configuration " +
                                "file as a target to be identified by Prometheus before it can be used here. " +
                                "By default, the HTTP server is initiated at \'http://localhost:9080\'.",
                        defaultValue = "http://localhost:9080",
                        possibleParameters = "Any valid URL"
                ),
                @SystemParameter(
                        name = "pushURL",
                        description = "This property configures the target URL of the Prometheus pushGateway " +
                                "(where the pushGateway needs to listen). This URL needs to be defined in the " +
                                "Prometheus configuration file as a target to be identified by Prometheus before it " +
                                "can be used here.",
                        defaultValue = "http://localhost:9091",
                        possibleParameters = "Any valid URL"
                ),
                @SystemParameter(
                        name = "groupingKey",
                        description = "This property configures the grouping key of created metrics in key-value " +
                                "pairs. Grouping key is used only in pushGateway mode in order to distinguish these " +
                                "metrics from already existing metrics under the same job. " +
                                "The expected format of the grouping key is as follows: " +
                                "\"'key1:value1','key2:value2'\" .",
                        defaultValue = "null",
                        possibleParameters = "Any key value pairs in the supported format"
                )
        }
)

public class PrometheusSink extends Sink<PrometheusSink.PrometheusSinkState> {
    private static final Logger log = LogManager.getLogger(PrometheusSink.class);

    private String jobName;
    private String pushURL;
    private String serverURL;
    private String publishMode;
    private Collector.Type metricType;
    private String metricHelp;
    private String metricName;
    private List<String> attributes;
    private String buckets;
    private String quantiles;
    private String pushOperation;
    private Map<String, String> groupingKey;
    private String valueAttribute;
    private double quantileError;

    private PrometheusMetricBuilder prometheusMetricBuilder;
    private HTTPServer server;
    private PushGateway pushGateway;
    private CollectorRegistry collectorRegistry;
    private String registeredMetrics;
    private ConfigReader configReader;

    @Override
    public Class[] getSupportedInputEventClasses() {
        return new Class[]{Map.class};
    }

    @Override
    protected ServiceDeploymentInfo exposeServiceDeploymentInfo() {

        return null;
    }

    @Override
    public String[] getSupportedDynamicOptions() {
        return new String[0];
    }

    @Override
    protected StateFactory<PrometheusSinkState> init(StreamDefinition outputStreamDefinition, OptionHolder optionHolder,
                                                     ConfigReader configReader, SiddhiAppContext siddhiAppContext) {
        String streamID = outputStreamDefinition.getId();
        if (!optionHolder.isOptionExists(PrometheusConstants.METRIC_TYPE)) {
            throw new SiddhiAppCreationException("The mandatory field \'metric.type\' is not found in Prometheus " +
                    "sink associated with stream \'" + streamID + " \'");
        }
        //check for custom mapping
        List<Annotation> annotations = outputStreamDefinition.getAnnotations();
        for (Annotation annotation : annotations) {
            List<Annotation> mapAnnotation = annotation.getAnnotations(PrometheusConstants.MAP_ANNOTATION);
            for (Annotation annotationMap : mapAnnotation) {
                if (!annotationMap.getAnnotations(PrometheusConstants.PAYLOAD_ANNOTATION).isEmpty()) {
                    throw new SiddhiAppCreationException("Custom mapping associated with stream \'" +
                            streamID + "\' is not supported by Prometheus sink");
                }
            }
        }
        this.jobName = optionHolder.validateAndGetStaticValue(PrometheusConstants.JOB_NAME,
                PrometheusSinkUtil.configureJobName(configReader));
        this.pushURL = optionHolder.validateAndGetStaticValue(PrometheusConstants.PUSH_URL,
                PrometheusSinkUtil.configurePushURL(configReader));
        this.serverURL = optionHolder.validateAndGetStaticValue(PrometheusConstants.SERVER_URL,
                PrometheusSinkUtil.configureServerURL(configReader));
        this.publishMode = optionHolder.validateAndGetStaticValue(PrometheusConstants.METRIC_PUBLISH_MODE,
                PrometheusSinkUtil.configurePublishMode(configReader));
        this.buckets = optionHolder.validateAndGetStaticValue(PrometheusConstants.BUCKET_DEFINITION, EMPTY_STRING);
        this.quantiles = optionHolder.validateAndGetStaticValue(PrometheusConstants.QUANTILES_DEFINITION, EMPTY_STRING);
        this.attributes = outputStreamDefinition.getAttributeList()
                .stream().map(Attribute::getName).collect(Collectors.toList());
        this.metricName = optionHolder.validateAndGetStaticValue(
                PrometheusConstants.METRIC_NAME, streamID.trim());
        this.metricType = PrometheusSinkUtil.assignMetricType(optionHolder.validateAndGetStaticValue(METRIC_TYPE),
                streamID);
        this.metricHelp = optionHolder.validateAndGetStaticValue(PrometheusConstants.METRIC_HELP,
                HELP_STRING + PrometheusSinkUtil.getMetricTypeString(metricType) + SPACE_STRING +
                        metricName).trim();
        this.pushOperation = optionHolder.validateAndGetStaticValue(
                PrometheusConstants.PUSH_DEFINITION, PrometheusConstants.PUSH_ADD_OPERATION).trim();
        this.groupingKey = PrometheusSinkUtil.populateGroupingKey(optionHolder.validateAndGetStaticValue(
                PrometheusConstants.GROUPING_KEY_DEFINITION,
                PrometheusSinkUtil.configureGroupinKey(configReader)).trim(),
                streamID);
        this.valueAttribute = optionHolder.validateAndGetStaticValue(
                PrometheusConstants.VALUE_ATTRIBUTE, VALUE_STRING).trim();
        try {
            this.quantileError = parseDouble(optionHolder.validateAndGetStaticValue(
                    PrometheusConstants.QUANTILE_ERROR, DEFAULT_ERROR));
            if (quantileError < 0 || quantileError >= 1.0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new SiddhiAppCreationException("Invalid value for \'quantile.error\' in Prometheus sink " +
                    "associated with stream \'" + streamID + "\'. Value must be between 0 and 1");
        }

        if (!publishMode.equalsIgnoreCase(SERVER_PUBLISH_MODE) &&
                !publishMode.equalsIgnoreCase(PUSHGATEWAY_PUBLISH_MODE)) {
            throw new SiddhiAppCreationException("Invalid publish mode : " + publishMode + " in Prometheus sink " +
                    "associated with stream \'" + streamID + "\'.");
        }

        if (!metricName.matches(PrometheusConstants.METRIC_NAME_REGEX)) {
            throw new SiddhiAppCreationException("Metric name \'" + metricName + "\' does not match the regex " +
                    "\"[a-zA-Z_:][a-zA-Z0-9_:]*\" in Prometheus sink associated with stream \'"
                    + streamID + "\'.");
        }

        if (!pushOperation.equalsIgnoreCase(PUSH_OPERATION) &&
                !pushOperation.equalsIgnoreCase(PUSH_ADD_OPERATION)) {
            throw new SiddhiAppCreationException("Invalid value for push operation : " + pushOperation +
                    " in Prometheus sink associated with stream \'" + streamID + "\'.");
        }

        // checking for value attribute and its type in stream definintion
        try {
            Attribute.Type valueType = outputStreamDefinition.getAttributeType(valueAttribute);
            if (valueType.equals(Attribute.Type.STRING) || valueType.equals(Attribute.Type.BOOL) ||
                    valueType.equals(Attribute.Type.OBJECT)) {
                throw new SiddhiAppCreationException("The field value attribute \'" + valueAttribute + " \'contains " +
                        "unsupported type in Prometheus sink associated with stream \'" + streamID + "\'");
            }
        } catch (AttributeNotExistException exception) {
            throw new SiddhiAppCreationException("The value attribute \'" + valueAttribute + "\' is not found " +
                    "in Prometheus sink associated with stream \'" + streamID + "\'");
        }

        // checking unsupported metric types for 'buckets'
        if (!buckets.isEmpty()) {
            if (metricType.equals(Collector.Type.COUNTER) ||
                    metricType.equals(Collector.Type.GAUGE) || metricType.equals(Collector.Type.SUMMARY)) {
                throw new SiddhiAppCreationException("The buckets field in Prometheus sink associated with stream \'" +
                        streamID + "\' is not supported " +
                        "for metric type \'" + metricType + "\'.");
            }
        }
        // checking unsupported metric types for 'quantiles' and unsupported values for quantiles
        if (!quantiles.isEmpty()) {
            if (metricType.equals(Collector.Type.COUNTER) ||
                    metricType.equals(Collector.Type.GAUGE) || metricType.equals(Collector.Type.HISTOGRAM)) {
                throw new SiddhiAppCreationException("The quantiles field in Prometheus sink associated with " +
                        "stream \'" + streamID + "\' is not supported " +
                        "for metric type \'" + metricType + "\'.");
            }
        }
        prometheusMetricBuilder = new PrometheusMetricBuilder(metricName, metricHelp, metricType, attributes);
        prometheusMetricBuilder.setHistogramBuckets(PrometheusSinkUtil.convertToDoubleArray(buckets.trim(), streamID));
        double[] quantileValues = PrometheusSinkUtil.convertToDoubleArray(quantiles.trim(), streamID);
        if (PrometheusSinkUtil.validateQuantiles(quantileValues, streamID)) {
            prometheusMetricBuilder.setQuantiles(quantileValues, quantileError);
        }
        switch (publishMode) {
            case PrometheusConstants.SERVER_PUBLISH_MODE:
                collectorRegistry = prometheusMetricBuilder.setRegistry(serverURL, streamID);
                break;
            case PrometheusConstants.PUSHGATEWAY_PUBLISH_MODE:
                collectorRegistry = prometheusMetricBuilder.setRegistry(pushURL, streamID);
                break;
            default:
                //default execution is not needed
        }
        return () -> new PrometheusSinkState();
    }

    @Override
    public void publish(Object payload, DynamicOptions dynamicOptions, PrometheusSinkState state)
            throws ConnectionUnavailableException {
        Map<String, Object> attributeMap = (Map<String, Object>) payload;
        String[] labels;
        double value = parseDouble(attributeMap.get(valueAttribute).toString());
        labels = PrometheusSinkUtil.populateLabelArray(attributeMap, valueAttribute);
        prometheusMetricBuilder.insertValues(value, labels);
        if ((PrometheusConstants.PUSHGATEWAY_PUBLISH_MODE).equals(publishMode)) {
            try {
                switch (pushOperation) {
                    case PrometheusConstants.PUSH_OPERATION:
                        pushGateway.push(collectorRegistry, jobName, groupingKey);
                        break;
                    case PrometheusConstants.PUSH_ADD_OPERATION:
                        pushGateway.pushAdd(collectorRegistry, jobName, groupingKey);
                        break;
                    default:
                        //default will never be executed
                }
            } catch (IOException e) {
                log.error("Unable to establish connection for Prometheus sink associated with " +
                        "stream \'" + getStreamDefinition().getId() + "\' at " + pushURL);
                throw new ConnectionUnavailableException("Unable to establish connection for Prometheus sink " +
                        "associated with stream \'" + getStreamDefinition().getId() + "\' at " + pushURL, e);
            }
        }
    }

    @Override
    public void connect() throws ConnectionUnavailableException {
        try {
            URL target;
            switch (publishMode) {
                case PrometheusConstants.SERVER_PUBLISH_MODE:
                    target = new URL(serverURL);
                    initiateServer(target.getHost(), target.getPort());
                    log.info(getStreamDefinition().getId() + " has successfully connected at " + serverURL);
                    break;
                case PrometheusConstants.PUSHGATEWAY_PUBLISH_MODE:
                    target = new URL(pushURL);
                    pushGateway = new PushGateway(target);
                    try {
                        pushGateway.pushAdd(collectorRegistry, jobName, groupingKey);
                        log.info(getStreamDefinition().getId() + " has successfully connected to pushGateway at "
                                + pushURL);
                    } catch (IOException e) {
                        if (e.getMessage().equalsIgnoreCase("Connection refused (Connection refused)")) {
                            log.error("The stream \'" + getStreamDefinition().getId() + "\' of Prometheus sink " +
                                            "could not connect to Pushgateway." +
                                            " Prometheus pushgateway is not listening at " + target);
                            throw new ConnectionUnavailableException("The stream \'" + getStreamDefinition().getId() +
                                    "\' of Prometheus sink could not connect to Pushgateway." +
                                    " Prometheus pushgateway is not listening at " + target, e);
                        }
                    }
                    break;
                default:
                    //default will never be executed
            }
            prometheusMetricBuilder.registerMetric(valueAttribute);
        } catch (MalformedURLException e) {
            throw new ConnectionUnavailableException("Error in URL format in Prometheus sink associated with stream \'"
                    + getStreamDefinition().getId() + "\'. \n ", e);
        }
    }

    private void initiateServer(String host, int port) throws ConnectionUnavailableException {
        try {
            InetSocketAddress address = new InetSocketAddress(host, port);
            server = new HTTPServer(address, collectorRegistry);
        } catch (IOException e) {
            if (!(e instanceof BindException && e.getMessage().equals("Address already in use"))) {
                log.error("Unable to establish connection for Prometheus sink associated with stream \'" +
                                getStreamDefinition().getId() + "\' at " + serverURL);
                throw new ConnectionUnavailableException("Unable to establish connection for Prometheus sink " +
                        "associated with stream \'" + getStreamDefinition().getId() + "\' at " + serverURL, e);
            }
        }
    }

    @Override
    public void disconnect() {
        if (server != null) {
            server.stop();
            log.info("Server successfully stopped at " + serverURL);
        }
    }

    @Override
    public void destroy() {
        if (collectorRegistry != null) {
            collectorRegistry.clear();
        }
    }

    private String assignRegisteredMetrics() {
        Enumeration<Collector.MetricFamilySamples> registeredMetricSamples = prometheusMetricBuilder.
                getRegistry().metricFamilySamples();
        Collector.MetricFamilySamples metricFamilySamples;
        while (registeredMetricSamples.hasMoreElements()) {
            metricFamilySamples = registeredMetricSamples.nextElement();
            if (metricFamilySamples.name.equals(metricName)) {
                return metricFamilySamples.toString();
            }
        }
        return "";
    }

    class PrometheusSinkState extends State {

        @Override
        public boolean canDestroy() {
            return false;
        }

        @Override
        public Map<String, Object> snapshot() {
            Map<String, Object> currentMetrics = new HashMap<>();
            currentMetrics.put(PrometheusConstants.REGISTERED_METRICS, assignRegisteredMetrics());
            return currentMetrics;
        }

        @Override
        public void restore(Map<String, Object> map) {
            Object currentMetricSample = map.get(PrometheusConstants.REGISTERED_METRICS);
            if (!currentMetricSample.equals(EMPTY_STRING)) {
                registeredMetrics = currentMetricSample.toString();
            }
        }
    }
}

