/*
 *  Copyright (c) 2017 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.extension.siddhi.io.http.sink;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.apache.log4j.Logger;
import org.wso2.carbon.messaging.Header;
import org.wso2.extension.siddhi.io.http.sink.util.HttpSinkUtil;
import org.wso2.extension.siddhi.io.http.util.HttpConstants;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.SystemParameter;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.exception.ConnectionUnavailableException;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.stream.output.sink.Sink;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.transport.DynamicOptions;
import org.wso2.siddhi.core.util.transport.Option;
import org.wso2.siddhi.core.util.transport.OptionHolder;
import org.wso2.siddhi.query.api.definition.StreamDefinition;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.common.ProxyServerConfiguration;
import org.wso2.transport.http.netty.config.SenderConfiguration;
import org.wso2.transport.http.netty.contract.HttpClientConnector;
import org.wso2.transport.http.netty.contract.HttpWsConnectorFactory;
import org.wso2.transport.http.netty.contractimpl.HttpWsConnectorFactoryImpl;
import org.wso2.transport.http.netty.message.HTTPCarbonMessage;

import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/**
 * {@code HttpSink } Handle the HTTP publishing tasks.
 */
@Extension(name = "http", namespace = "sink",
        description = "This extension publish the HTTP events in any HTTP method  POST, GET, PUT, DELETE  via HTTP " +
                "or https protocols. As the additional features this component can provide basic authentication " +
                "as well as user can publish events using custom client truststore files when publishing events " +
                "via https protocol. And also user can add any number of headers including HTTP_METHOD header for " +
                "each event dynamically.",
        parameters = {
                @Parameter(
                        name = "publisher.url",
                        description = "The URL to which the outgoing events should be published via HTTP. " +
                                "This is a mandatory parameter and if this is not specified, an error is logged in " +
                                "the CLI. If user wants to enable SSL for the events, use `https` instead of `http` " +
                                "in the publisher.url." +
                                "e.g., " +
                                "`http://localhost:8080/endpoint`, "
                                + "`https://localhost:8080/endpoint`",
                        type = {DataType.STRING}),
                @Parameter(
                        name = "basic.auth.username",
                        description = "The username to be included in the authentication header of the basic " +
                                "authentication enabled events. It is required to specify both username and " +
                                "password to enable basic authentication. If one of the parameter is not given " +
                                "by user then an error is logged in the CLI.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = " "),
                @Parameter(
                        name = "basic.auth.password",
                        description = "The password to include in the authentication header of the basic " +
                                "authentication enabled events. It is required to specify both username and " +
                                "password to enable basic authentication. If one of the parameter is not given " +
                                "by user then an error is logged in the CLI.",
                        type = {DataType.STRING},
                        optional = true, defaultValue = " "),
                @Parameter(
                        name = "https.truststore.file",
                        description = "The file path to the location of the truststore of the client that sends " +
                                "the HTTP events through 'https' protocol. A custom client-truststore can be " +
                                "specified if required.",
                        type = {DataType.STRING},
                        optional = true, defaultValue = "${carbon.home}/resources/security/client-truststore.jks"),
                @Parameter(
                        name = "https.truststore.password",
                        description = "The password for the client-truststore. A custom password can be specified " +
                                "if required. If no custom password is specified and the protocol of URL is 'https' " +
                                "then, the system uses default password.",
                        type = {DataType.STRING},
                        optional = true, defaultValue = "wso2carbon"),
                @Parameter(
                        name = "headers",
                        description = "The headers that should be included as a HTTP request headers. There can be " +
                                "any number of headers concatenated on following format. " +
                                "header1:value1#header2:value2. User can include content-type header if he need to " +
                                "any specific type for payload if not system get the mapping type as the content-Type" +
                                " header (ie. @map(xml):application/xml,@map(json):application/json,@map(text)" +
                                ":plain/text ) and if user does not include any mapping type then system gets the " +
                                "'plain/text' as default Content-Type header. If user does not include " +
                                "Content-Length header then system calculate the bytes size of payload and include it" +
                                " as content-length header.",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = " "),
                @Parameter(
                        name = "method",
                        description = "For HTTP events, HTTP_METHOD header should be included as a request header." +
                                " If the parameter is null then system uses 'POST' as a default header.",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "POST"),
                @Parameter(
                        name = "socket.idle.timeout",
                        description = "TODO",
                        type = {DataType.INT},
                        optional = true,
                        dynamic = true, defaultValue = "6000"),
                @Parameter(
                        name = "chunk.disabled",
                        description = "TODO",
                        type = {DataType.BOOL},
                        optional = true,
                        dynamic = true, defaultValue = "false"),
                @Parameter(
                        name = "ssl.protocol",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TLS"),
                @Parameter(
                        name = "ciphers",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "null"),
                @Parameter(
                        name = "sslEnabledProtocols",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "null"),
                @Parameter(
                        name = "Client.enable.session.creation",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "null"),
                @Parameter(
                        name = "follow.redirect",
                        description = "TODO",
                        type = {DataType.BOOL},
                        optional = true,
                        dynamic = true, defaultValue = "true"),
                @Parameter(
                        name = "max.redirect.count",
                        description = "TODO",
                        type = {DataType.INT},
                        optional = true,
                        dynamic = true, defaultValue = "5"),
                @Parameter(
                        name = "tls.store.type",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "JKS"),
                @Parameter(
                        name = "proxy.host",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "null"),
                @Parameter(
                        name = "proxy.port",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "null"),
                @Parameter(
                        name = "proxy.username",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "null"),
                @Parameter(
                        name = "proxy.password",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "null"),
                //bootstrap configurations
                @Parameter(
                        name = "client.bootstrap.nodelay",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.bootstrap.keepalive",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.bootstrap.sendbuffersize",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.bootstrap.recievebuffersize",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.bootstrap.connect.timeout",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.bootstrap.socket.reuse",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.bootstrap.socket.timeout",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.bootstrap.worker.group.size",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.connection.pool.count",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.max.active.connections.per.pool",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.min.idle.connections.per.pool",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.max.idle.connections.per.pool",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "client.min.eviction.idle.time",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "sender.thread.count",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "event.group.executor.thread.size",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO"),
                @Parameter(
                        name = "max.wait.for.client.connection.pool",
                        description = "TODO",
                        type = {DataType.STRING},
                        optional = true,
                        dynamic = true, defaultValue = "TODO")
        },
        examples = {
                @Example(syntax =
                        "@sink(type='http',publisher.url='http://localhost:8009/foo', method='{{method}}',"
                                + "headers='{{headers}}',client.bootstrap.configuration=\"'client.bootstrap.socket" +
                                ".timeout:20','client.bootstrap.worker.group.size:10'\",client.pool" +
                                ".configuration=\"'client.connection.pool.count:10','client.max.active.connections" +
                                ".per.pool:1'\" "
                                + "@map(type='xml' , @payload('{{payloadBody}}')))"
                                + "define stream FooStream (payloadBody String, method string, headers string);\n",
                        description =
                                "If it is xml mapping expected input should be in following format for FooStream:"
                                        + "{"
                                        + "<events>"
                                        + "    <event>"
                                        + "        <symbol>WSO2</symbol>"
                                        + "        <price>55.6</price>"
                                        + "        <volume>100</volume>"
                                        + "    </event>"
                                        + "</events>,"
                                        + "POST,"
                                        + "Content-Length:24#Content-Location:USA#Retry-After:120"
                                        + "}"

                                        + "Above event will generate output as below."
                                        + "~Output http event payload"
                                        + "<events>\n"
                                        + "    <event>\n"
                                        + "        <symbol>WSO2</symbol>\n"
                                        + "        <price>55.6</price>\n"
                                        + "        <volume>100</volume>\n"
                                        + "    </event>\n"
                                        + "</events>\n"
                                        + "~Output http event headers"
                                        + "Content-Length:24,"
                                        + "Content-Location:'USA',"
                                        + "Retry-After:120,"
                                        + "Content-Type:'application/xml',"
                                        + "HTTP_METHOD:'POST',"
                                        + "~Output http event properties"
                                        + "HTTP_METHOD:'POST',"
                                        + "HOST:'localhost',"
                                        + "PORT:8009"
                                        + "PROTOCOL:'http'"
                                        + "TO:'/foo'"
                )},
        systemParameter = {
                @SystemParameter(
                        name = "clientBootstrapBossGroupSize",
                        description = "property to configure number of boss threads, which accepts incoming " +
                                "connections until the ports are unbound. Once connection accepts successfully, " +
                                "boss thread passes the accepted channel to one of the worker threads.",
                        defaultValue = "4",
                        possibleParameters = "Any integer"
                ),
                @SystemParameter(
                        name = "clientBootstrapWorkerGroupSize",
                        description = "property to configure number of worker threads, which performs non " +
                                "blocking read and write for one or more channels in non-blocking mode.",
                        defaultValue = "8",
                        possibleParameters = "Any integer"
                ),
                @SystemParameter(
                        name = "trustStoreLocation",
                        description = "The default truststore file path.",
                        defaultValue = "${carbon.home}/resources/security/client-truststore.jks",
                        possibleParameters = "Path to client-truststore.jks"
                ),
                @SystemParameter(
                        name = "trustStorePassword",
                        description = "The default truststore password.",
                        defaultValue = "wso2carbon",
                        possibleParameters = "Truststore password"
                )
        }
)
public class HttpSink extends Sink {
    private static final Logger log = Logger.getLogger(HttpSink.class);
    private String streamID;
    private HttpClientConnector clientConnector;
    private String mapType;
    private Map<String, String> httpURLProperties;
    private Option httpHeaderOption;
    private Option httpMethodOption;
    private String authorizationHeader;
    private String userName;
    private String userPassword;
    private String publisherURL;

    /**
     * Returns the list of classes which this sink can consume.
     * Based on the type of the sink, it may be limited to being able to publish specific type of classes.
     * For example, a sink of type file can only write objects of type String .
     *
     * @return array of supported classes , if extension can support of any types of classes
     * then return empty array .
     */
    @Override
    public Class[] getSupportedInputEventClasses() {
        return new Class[]{String.class};
    }

    /**
     * Returns a list of supported dynamic options (that means for each event value of the option can change) by
     * the transport
     *
     * @return the list of supported dynamic option keys
     */
    @Override
    public String[] getSupportedDynamicOptions() {
        return new String[]{HttpConstants.HEADERS, HttpConstants.METHOD};
    }

    /**
     * The initialization method for {@link Sink}, which will be called before other methods and validate
     * the all configuration and getting the intial values.
     *
     * @param outputStreamDefinition containing stream definition bind to the {@link Sink}
     * @param optionHolder           Option holder containing static and dynamic configuration related
     *                               to the {@link Sink}
     * @param configReader           to read the sink related system configuration.
     * @param siddhiAppContext       the context of the {@link org.wso2.siddhi.query.api.SiddhiApp} used to
     *                               get siddhi related utilty functions.
     */
    @Override
    protected void init(StreamDefinition outputStreamDefinition, OptionHolder optionHolder,
                        ConfigReader configReader, SiddhiAppContext siddhiAppContext) {
        //read configurations
        this.streamID = siddhiAppContext.getName() + ":" + outputStreamDefinition.toString();
        this.mapType = outputStreamDefinition.getAnnotations().get(0).getAnnotations().get(0).getElements().get(0)
                .getValue();
        this.publisherURL = optionHolder.validateAndGetStaticValue(HttpConstants.PUBLISHER_URL);
        this.httpHeaderOption = optionHolder.getOrCreateOption(HttpConstants.HEADERS, HttpConstants.DEFAULT_HEADER);
        this.httpMethodOption = optionHolder.getOrCreateOption(HttpConstants.METHOD, HttpConstants.DEFAULT_METHOD);
        this.userName = optionHolder.validateAndGetStaticValue(HttpConstants.RECEIVER_USERNAME,
                HttpConstants.EMPTY_STRING);
        this.userPassword = optionHolder.validateAndGetStaticValue(HttpConstants.RECEIVER_PASSWORD,
                HttpConstants.EMPTY_STRING);
        String clientStoreFile = optionHolder.validateAndGetStaticValue(HttpConstants.CLIENT_TRUSTSTORE_PATH_PARAM,
                HttpSinkUtil.trustStorePath(configReader));
        String clientStorePass = optionHolder.validateAndGetStaticValue(HttpConstants.CLIENT_TRUSTSTORE_PASSWORD_PARAM,
                HttpSinkUtil.trustStorePassword(configReader));
        String scheme = HttpSinkUtil.getScheme(publisherURL);
        this.httpURLProperties = HttpSinkUtil.getURLProperties(publisherURL);
        int socketIdleTimeout = Integer.parseInt(optionHolder.validateAndGetStaticValue
                (HttpConstants.SOCKET_IDEAL_TIMEOUT, "-1"));
        String sslProtocol = optionHolder.validateAndGetStaticValue(HttpConstants.SSL_PROTOCOL, HttpConstants
                .EMPTY_STRING);
        String tlsStoreType = optionHolder.validateAndGetStaticValue(HttpConstants.TLS_STORE_TYPE, HttpConstants
                .EMPTY_STRING);
        String chunkDisabled = optionHolder.validateAndGetStaticValue(HttpConstants.CLIENT_CHUNK_ENABLED,
                HttpConstants.EMPTY_STRING);
        String followRedirect = optionHolder.validateAndGetStaticValue(HttpConstants.CLIENT_FOLLOW_REDIRECT,
                HttpConstants.EMPTY_STRING);
        String maxRedirectCount = optionHolder.validateAndGetStaticValue(HttpConstants.CLIENT_MAX_REDIRECT_COUNT,
                HttpConstants.EMPTY_STRING);
        String parametersList = optionHolder.validateAndGetStaticValue(HttpConstants.SINK_PARAMETERS,
                HttpConstants.EMPTY_STRING);
        String proxyHost = optionHolder.validateAndGetStaticValue(HttpConstants.PROXY_HOST, HttpConstants.EMPTY_STRING);
        String proxyPort = optionHolder.validateAndGetStaticValue(HttpConstants.PROXY_PORT, HttpConstants.EMPTY_STRING);
        String proxyUsername = optionHolder.validateAndGetStaticValue(HttpConstants.PROXY_USERNAME, HttpConstants
                .EMPTY_STRING);
        String proxyPassword = optionHolder.validateAndGetStaticValue(HttpConstants.PROXY_PASSWORD, HttpConstants
                .EMPTY_STRING);
        String clientBootstrapConfiguration = optionHolder.validateAndGetStaticValue(HttpConstants
                .CLIENT_BOOTSTRAP_CONFIGURATION, HttpConstants.EMPTY_STRING);
        String clientPoolConfiguration = optionHolder.validateAndGetStaticValue(HttpConstants
                .CLIENT_POOL_CONFIGURATION, HttpConstants.EMPTY_STRING);
        //read trp globe configuration
        String bootstrapWorker = configReader.readConfig(HttpConstants
                .CLIENT_BOOTSTRAP_WORKER_GROUP_SIZE, HttpConstants.CLIENT_BOOTSTRAP_WORKER_GROUP_SIZE_VALUE);
        String bootstrapBoss = configReader.readConfig(HttpConstants
                .CLIENT_BOOTSTRAP_BOSS_GROUP_SIZE, HttpConstants.CLIENT_BOOTSTRAP_BOSS_GROUP_SIZE_VALUE);
        //Generate basic sender configurations
        SenderConfiguration senderConfig = HttpSinkUtil.getSenderConfigurations(httpURLProperties,
                clientStoreFile, clientStorePass, configReader);
        if (HttpConstants.EMPTY_STRING.equals(publisherURL)) {
            throw new SiddhiAppCreationException("Receiver URL found empty but it is Mandatory field in " +
                    "" + HttpConstants.HTTP_SINK_ID + "in" + streamID);
        }
        if (HttpConstants.SCHEME_HTTPS.equals(scheme) && ((clientStoreFile == null) || (clientStorePass == null))) {
            throw new ExceptionInInitializerError("Client trustStore file path or password are empty while " +
                    "default scheme is 'https'. Please provide client " +
                    "trustStore file path and password in" + streamID);
        }
        //if username and password both not equal to null consider as basic auth enabled if only one is null take it
        // as exception
        if ((HttpConstants.EMPTY_STRING.equals(userName) ^
                HttpConstants.EMPTY_STRING.equals(userPassword))) {
            throw new SiddhiAppCreationException("Please provide user name and password in " +
                    HttpConstants.HTTP_SINK_ID + "in" + streamID);
        } else if (!(HttpConstants.EMPTY_STRING.equals(userName) || HttpConstants.EMPTY_STRING.equals
                (userPassword))) {
            byte[] val = (userName + HttpConstants.AUTH_USERNAME_PASSWORD_SEPARATOR + userPassword).getBytes(Charset
                    .defaultCharset());
            this.authorizationHeader = HttpConstants.AUTHORIZATION_METHOD + Base64.encode
                    (Unpooled.copiedBuffer(val));
        }
        //if bootstrap configurations are given then pass it if not let take default value of transport
        HttpWsConnectorFactory httpConnectorFactory;
        if (!HttpConstants.EMPTY_STRING.equals(bootstrapBoss) && !HttpConstants.EMPTY_STRING.equals(bootstrapWorker)) {
            httpConnectorFactory = new HttpWsConnectorFactoryImpl(Integer.parseInt(bootstrapBoss), Integer.parseInt
                    (bootstrapWorker));
        } else {
            httpConnectorFactory = new HttpWsConnectorFactoryImpl();
        }

        //if proxy username and password not equal to null then create proxy configurations
        if (!HttpConstants.EMPTY_STRING.equals(proxyHost) && !HttpConstants.EMPTY_STRING.equals(proxyPort)) {
            try {
                ProxyServerConfiguration proxyServerConfiguration = new ProxyServerConfiguration(proxyHost, Integer
                        .parseInt(proxyPort));
                if (!HttpConstants.EMPTY_STRING.equals(proxyPassword) && !HttpConstants.EMPTY_STRING.equals
                        (proxyUsername)) {
                    proxyServerConfiguration.setProxyPassword(proxyPassword);
                    proxyServerConfiguration.setProxyUsername(proxyUsername);
                }
                senderConfig.setProxyServerConfiguration(proxyServerConfiguration);
            } catch (UnknownHostException e) {
                log.error("Proxy url and password is invalid in sink " + streamID, e);
            }
        }
        //add advanced sender configurations
        if (socketIdleTimeout != -1) {
            senderConfig.setSocketIdleTimeout(socketIdleTimeout);
        }
        if (!HttpConstants.EMPTY_STRING.equals(sslProtocol)) {
            senderConfig.setSslProtocol(sslProtocol);
        }
        if (!HttpConstants.EMPTY_STRING.equals(tlsStoreType)) {
            senderConfig.setTlsStoreType(tlsStoreType);
        }
        if (!HttpConstants.EMPTY_STRING.equals(chunkDisabled)) {
            senderConfig.setChunkDisabled(Boolean.parseBoolean(chunkDisabled));
        }
        if (!HttpConstants.EMPTY_STRING.equals(followRedirect)) {
            senderConfig.setFollowRedirect(Boolean.parseBoolean(followRedirect));
        }
        if (!HttpConstants.EMPTY_STRING.equals(maxRedirectCount)) {
            senderConfig.setMaxRedirectCount(Integer.parseInt(maxRedirectCount));
        }
        if (!HttpConstants.EMPTY_STRING.equals(parametersList)) {
            senderConfig.setParameters(HttpSinkUtil.populateParameters(parametersList));
        }

        //overwrite default transport configuration
        Map<String, Object> properties = HttpSinkUtil.populateTransportConfiguration(clientBootstrapConfiguration,
                clientPoolConfiguration);

        clientConnector = httpConnectorFactory.createHttpClientConnector(properties, senderConfig);
    }


    /**
     * This method will be called when events need to be published via this sink
     *
     * @param payload        payload of the event based on the supported event class exported by the extensions
     * @param dynamicOptions holds the dynamic options of this sink and Use this object to obtain dynamic options.
     * @throws ConnectionUnavailableException if end point is unavailable the ConnectionUnavailableException thrown
     *                                        such that the  system will take care retrying for connection
     */
    @Override
    public void publish(Object payload, DynamicOptions dynamicOptions) throws ConnectionUnavailableException {
        String headers = httpHeaderOption.getValue(dynamicOptions);
        String httpMethod = HttpConstants.EMPTY_STRING.equals(httpMethodOption.getValue(dynamicOptions)) ?
                HttpConstants.METHOD_DEFAULT : httpMethodOption.getValue(dynamicOptions);
        List<Header> headersList = HttpSinkUtil.getHeaders(headers);
        String contentType = HttpSinkUtil.getContentType(mapType, headersList);
        String messageBody = (String) payload;
        HTTPCarbonMessage cMessage = createHttpCarbonMessage(httpMethod);
        cMessage = generateCarbonMessage(headersList, contentType, httpMethod, cMessage);
        cMessage.addHttpContent(new DefaultLastHttpContent(Unpooled.wrappedBuffer(messageBody
                .getBytes(Charset.defaultCharset()))));
        clientConnector.send(cMessage);

    }

    public HTTPCarbonMessage createHttpCarbonMessage(String method) {
        HTTPCarbonMessage httpCarbonMessage = null;
        switch (method) {
            case "GET": {
                httpCarbonMessage = new HTTPCarbonMessage(
                        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, ""));
                break;
            }
            case "PUT": {
                httpCarbonMessage = new HTTPCarbonMessage(
                        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PUT, ""));
                break;
            }
            case "PATCH": {
                httpCarbonMessage = new HTTPCarbonMessage(
                        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.PATCH, ""));
                break;
            }
            case "DELETE": {
                httpCarbonMessage = new HTTPCarbonMessage(
                        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.DELETE, ""));
                break;
            }
            case "POST": {
                httpCarbonMessage = new HTTPCarbonMessage(
                        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, ""));
                break;
            }
            default: {
                log.error("Invalid request type.");

                break;
            }

        }
        return httpCarbonMessage;
    }

    /**
     * This method will be called before the processing method.
     * Intention to establish connection to publish event.
     *
     * @throws ConnectionUnavailableException if end point is unavailable the ConnectionUnavailableException thrown
     *                                        such that the  system will take care retrying for connection
     */
    @Override
    public void connect() throws ConnectionUnavailableException {
        log.info(streamID + " has successfully connected to " + publisherURL);

    }

    /**
     * Called after all publishing is done, or when {@link ConnectionUnavailableException} is thrown
     * Implementation of this method should contain the steps needed to disconnect from the sink.
     */
    @Override
    public void disconnect() {
        if (clientConnector != null) {
            clientConnector = null;
            log.info("Server connector for url " + publisherURL + " disconnected.");
        }
    }

    /**
     * The method can be called when removing an event receiver.
     * The cleanups that has to be done when removing the receiver has to be done here.
     */
    @Override
    public void destroy() {
        if (clientConnector != null) {
            clientConnector = null;
            log.info("Server connector for url " + publisherURL + " disconnected.");
        }
    }

    /**
     * Used to collect the serializable state of the processing element, that need to be
     * persisted for reconstructing the element to the same state on a different point of time
     * This is also used to identify the internal states and debuging
     *
     * @return all internal states should be return as an map with meaning full keys
     */
    @Override
    public Map<String, Object> currentState() {
        //no current state.
        return null;
    }

    /**
     * Used to restore serialized state of the processing element, for reconstructing
     * the element to the same state as if was on a previous point of time.
     *
     * @param state the stateful objects of the processing element as a map.
     *              This map will have the  same keys that is created upon calling currentState() method.
     */
    @Override
    public void restoreState(Map<String, Object> state) {
        //no need to maintain.
    }

    /**
     * The method is responsible of generating carbon message to send.
     *
     * @param headers     the headers set.
     * @param contentType the content type. Value is if user has to given it as a header or if not it is map type.
     * @param httpMethod  http method type.
     * @param cMessage    carbon message to be send to the endpoint.
     * @return generated carbon message.
     */
    private HTTPCarbonMessage generateCarbonMessage(List<Header> headers, String contentType,
                                                    String httpMethod, HTTPCarbonMessage cMessage) {
        //if Authentication enabled
        if (!(userName.equals(HttpConstants.EMPTY_STRING) || userPassword.equals
                (HttpConstants.EMPTY_STRING))) {
            cMessage.setHeader(HttpConstants.AUTHORIZATION_HEADER, authorizationHeader);
        }

        /*
         * set carbon message properties which is to be used in carbon transport.
         */
        // Set protocol type http or https
        cMessage.setProperty(Constants.PROTOCOL, httpURLProperties.get(HttpConstants.SCHEME));
        // Set uri
        cMessage.setProperty(Constants.TO, httpURLProperties.get(HttpConstants.TO));
        // set Host
        cMessage.setProperty(Constants.HOST, httpURLProperties.get(HttpConstants.HOST));
        //set port
        cMessage.setProperty(Constants.PORT, Integer.valueOf(httpURLProperties.get(HttpConstants.PORT)));
        // Set method
        cMessage.setProperty(HttpConstants.HTTP_METHOD, httpMethod);

        /*
         *set request headers.
         */
        // Set user given Headers
        if (headers != null) {
            HttpHeaders httpHeaders = cMessage.getHeaders();
            for (Header header : headers) {
                httpHeaders.set(header.getName(), header.getValue());
            }
        }
        // Set content type if content type s not included in headers
        if (contentType.contains(mapType)) {
            cMessage.setHeader(HttpConstants.HTTP_CONTENT_TYPE, contentType);
        }

        //set method-type header
        cMessage.setHeader(HttpConstants.HTTP_METHOD, httpMethod);
        //cMessage.setEndOfMsgAdded(true);
        return cMessage;
    }
}
