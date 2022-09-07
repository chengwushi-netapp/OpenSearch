/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.extensions;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.opensearch.test.ClusterServiceUtils.createClusterService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.junit.After;
import org.junit.Before;
import org.opensearch.Version;
import org.opensearch.action.admin.cluster.state.ClusterStateResponse;
import org.opensearch.client.node.NodeClient;
import org.opensearch.cluster.ClusterSettingsResponse;
import org.opensearch.cluster.LocalNodeResponse;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.io.PathUtils;
import org.opensearch.common.io.stream.InputStreamStreamInput;
import org.opensearch.common.io.stream.NamedWriteable;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.io.stream.NamedWriteableRegistryResponse;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.network.NetworkService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.transport.TransportAddress;
import org.opensearch.common.util.PageCacheRecycler;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.env.Environment;
import org.opensearch.env.TestEnvironment;
import org.opensearch.extensions.rest.RegisterRestActionsRequest;
import org.opensearch.extensions.rest.RegisterRestActionsResponse;
import org.opensearch.index.IndexModule;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.analysis.AnalysisRegistry;
import org.opensearch.index.engine.EngineConfigFactory;
import org.opensearch.index.engine.InternalEngineFactory;
import org.opensearch.indices.breaker.NoneCircuitBreakerService;
import org.opensearch.plugins.PluginInfo;
import org.opensearch.rest.RestController;
import org.opensearch.test.IndexSettingsModule;
import org.opensearch.test.MockLogAppender;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.test.transport.MockTransportService;
import org.opensearch.threadpool.TestThreadPool;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportResponse;
import org.opensearch.transport.TransportService;
import org.opensearch.transport.nio.MockNioTransport;
import org.opensearch.usage.UsageService;

public class ExtensionsOrchestratorTests extends OpenSearchTestCase {

    private TransportService transportService;
    private RestController restController;
    private ClusterService clusterService;
    private MockNioTransport transport;
    private Path extensionDir;
    private final ThreadPool threadPool = new TestThreadPool(ExtensionsOrchestratorTests.class.getSimpleName());
    private final Settings settings = Settings.builder()
        .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
        .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
        .build();
    private final List<String> extensionsYmlLines = Arrays.asList(
        "extensions:",
        "   - name: firstExtension",
        "     uniqueId: uniqueid1",
        "     hostName: 'myIndependentPluginHost1'",
        "     hostAddress: '127.0.0.0'",
        "     port: '9300'",
        "     version: '0.0.7'",
        "     description: Fake description 1",
        "     opensearchVersion: '3.0.0'",
        "     javaVersion: '14'",
        "     className: fakeClass1",
        "     customFolderName: fakeFolder1",
        "     hasNativeController: false",
        "   - name: secondExtension",
        "     uniqueId: 'uniqueid2'",
        "     hostName: 'myIndependentPluginHost2'",
        "     hostAddress: '127.0.0.1'",
        "     port: '9301'",
        "     version: '3.14.16'",
        "     description: Fake description 2",
        "     opensearchVersion: '2.0.0'",
        "     javaVersion: '17'",
        "     className: fakeClass2",
        "     customFolderName: fakeFolder2",
        "     hasNativeController: true"
    );

    @Before
    public void setup() throws Exception {
        Settings settings = Settings.builder().put("cluster.name", "test").build();
        transport = new MockNioTransport(
            settings,
            Version.CURRENT,
            threadPool,
            new NetworkService(Collections.emptyList()),
            PageCacheRecycler.NON_RECYCLING_INSTANCE,
            new NamedWriteableRegistry(Collections.emptyList()),
            new NoneCircuitBreakerService()
        );
        transportService = new MockTransportService(
            settings,
            transport,
            threadPool,
            TransportService.NOOP_TRANSPORT_INTERCEPTOR,
            (boundAddress) -> new DiscoveryNode(
                "test_node",
                "test_node",
                boundAddress.publishAddress(),
                emptyMap(),
                emptySet(),
                Version.CURRENT
            ),
            null,
            Collections.emptySet()
        );
        restController = new RestController(
            emptySet(),
            null,
            new NodeClient(Settings.EMPTY, threadPool),
            new NoneCircuitBreakerService(),
            new UsageService()
        );
        clusterService = createClusterService(threadPool);

        extensionDir = createTempDir();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
        transportService.close();
        ThreadPool.terminate(threadPool, 30, TimeUnit.SECONDS);
    }

    public void testExtensionsDiscovery() throws Exception {
        Path extensionDir = createTempDir();

        Files.write(extensionDir.resolve("extensions.yml"), extensionsYmlLines, StandardCharsets.UTF_8);

        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        List<DiscoveryExtension> expectedExtensionsList = new ArrayList<DiscoveryExtension>();

        expectedExtensionsList.add(
            new DiscoveryExtension(
                "firstExtension",
                "uniqueid1",
                "uniqueid1",
                "myIndependentPluginHost1",
                "127.0.0.0",
                new TransportAddress(InetAddress.getByName("127.0.0.0"), 9300),
                new HashMap<String, String>(),
                Version.fromString("3.0.0"),
                new PluginInfo(
                    "firstExtension",
                    "Fake description 1",
                    "0.0.7",
                    Version.fromString("3.0.0"),
                    "14",
                    "fakeClass1",
                    new ArrayList<String>(),
                    false
                )
            )
        );

        expectedExtensionsList.add(
            new DiscoveryExtension(
                "secondExtension",
                "uniqueid2",
                "uniqueid2",
                "myIndependentPluginHost2",
                "127.0.0.1",
                new TransportAddress(TransportAddress.META_ADDRESS, 9301),
                new HashMap<String, String>(),
                Version.fromString("2.0.0"),
                new PluginInfo(
                    "secondExtension",
                    "Fake description 2",
                    "3.14.16",
                    Version.fromString("2.0.0"),
                    "17",
                    "fakeClass2",
                    new ArrayList<String>(),
                    true
                )
            )
        );
        assertEquals(expectedExtensionsList.size(), extensionsOrchestrator.extensionIdMap.values().size());
        assertTrue(expectedExtensionsList.containsAll(extensionsOrchestrator.extensionIdMap.values()));
        assertTrue(extensionsOrchestrator.extensionIdMap.values().containsAll(expectedExtensionsList));
    }

    public void testNonUniqueExtensionsDiscovery() throws Exception {
        Path extensionDir = createTempDir();

        List<String> nonUniqueYmlLines = extensionsYmlLines.stream()
            .map(s -> s.replace("uniqueid2", "uniqueid1"))
            .collect(Collectors.toList());
        Files.write(extensionDir.resolve("extensions.yml"), nonUniqueYmlLines, StandardCharsets.UTF_8);

        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        List<DiscoveryExtension> expectedExtensionsList = new ArrayList<DiscoveryExtension>();

        expectedExtensionsList.add(
            new DiscoveryExtension(
                "firstExtension",
                "uniqueid1",
                "uniqueid1",
                "myIndependentPluginHost1",
                "127.0.0.0",
                new TransportAddress(InetAddress.getByName("127.0.0.0"), 9300),
                new HashMap<String, String>(),
                Version.fromString("3.0.0"),
                new PluginInfo(
                    "firstExtension",
                    "Fake description 1",
                    "0.0.7",
                    Version.fromString("3.0.0"),
                    "14",
                    "fakeClass1",
                    new ArrayList<String>(),
                    false
                )
            )
        );
        assertEquals(expectedExtensionsList.size(), extensionsOrchestrator.extensionIdMap.values().size());
        assertTrue(expectedExtensionsList.containsAll(extensionsOrchestrator.extensionIdMap.values()));
        assertTrue(extensionsOrchestrator.extensionIdMap.values().containsAll(expectedExtensionsList));
    }

    public void testNonAccessibleDirectory() throws Exception {
        AccessControlException e = expectThrows(

            AccessControlException.class,
            () -> new ExtensionsOrchestrator(settings, PathUtils.get(""))
        );
        assertEquals("access denied (\"java.io.FilePermission\" \"\" \"read\")", e.getMessage());
    }

    public void testNoExtensionsFile() throws Exception {
        Settings settings = Settings.builder().build();

        try (MockLogAppender mockLogAppender = MockLogAppender.createForLoggers(LogManager.getLogger(ExtensionsOrchestrator.class))) {

            mockLogAppender.addExpectation(
                new MockLogAppender.SeenEventExpectation(
                    "No Extensions File Present",
                    "org.opensearch.extensions.ExtensionsOrchestrator",
                    Level.INFO,
                    "Extensions.yml file is not present.  No extensions will be loaded."
                )
            );

            new ExtensionsOrchestrator(settings, extensionDir);

            mockLogAppender.assertAllExpectationsMatched();
        }
    }

    public void testEmptyExtensionsFile() throws Exception {
        Path extensionDir = createTempDir();

        List<String> emptyExtensionsYmlLines = Arrays.asList();
        Files.write(extensionDir.resolve("extensions.yml"), emptyExtensionsYmlLines, StandardCharsets.UTF_8);

        Settings settings = Settings.builder().build();

        expectThrows(IOException.class, () -> new ExtensionsOrchestrator(settings, extensionDir));
    }

    public void testExtensionsInitialize() throws Exception {
        Path extensionDir = createTempDir();

        Files.write(extensionDir.resolve("extensions.yml"), extensionsYmlLines, StandardCharsets.UTF_8);

        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        transportService.start();
        transportService.acceptIncomingRequests();
        extensionsOrchestrator.initializeServicesAndRestHandler(restController, transportService, clusterService);

        try (MockLogAppender mockLogAppender = MockLogAppender.createForLoggers(LogManager.getLogger(ExtensionsOrchestrator.class))) {

            mockLogAppender.addExpectation(
                new MockLogAppender.SeenEventExpectation(
                    "Connect Transport Exception 1",
                    "org.opensearch.extensions.ExtensionsOrchestrator",
                    Level.ERROR,
                    "ConnectTransportException[[firstExtension][127.0.0.0:9300] connect_timeout[30s]]"
                )
            );

            mockLogAppender.addExpectation(
                new MockLogAppender.SeenEventExpectation(
                    "Connect Transport Exception 2",
                    "org.opensearch.extensions.ExtensionsOrchestrator",
                    Level.ERROR,
                    "ConnectTransportException[[secondExtension][127.0.0.1:9301] connect_exception]; nested: ConnectException[Connection refused];"
                )
            );

            extensionsOrchestrator.extensionsInitialize();

            // Test needs to be changed to mock the connection between the local node and an extension. Assert statment is commented out for
            // now.
            // Link to issue: https://github.com/opensearch-project/OpenSearch/issues/4045
            // mockLogAppender.assertAllExpectationsMatched();
        }
    }

    public void testHandleRegisterRestActionsRequest() throws Exception {

        Path extensionDir = createTempDir();

        Files.write(extensionDir.resolve("extensions.yml"), extensionsYmlLines, StandardCharsets.UTF_8);

        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        extensionsOrchestrator.initializeServicesAndRestHandler(restController, transportService, clusterService);
        String uniqueIdStr = "uniqueid1";
        List<String> actionsList = List.of("GET /foo", "PUT /bar", "POST /baz");
        RegisterRestActionsRequest registerActionsRequest = new RegisterRestActionsRequest(uniqueIdStr, actionsList);
        TransportResponse response = extensionsOrchestrator.restActionsRequestHandler.handleRegisterRestActionsRequest(
            registerActionsRequest
        );
        assertEquals(RegisterRestActionsResponse.class, response.getClass());
        assertTrue(((RegisterRestActionsResponse) response).getResponse().contains(uniqueIdStr));
        assertTrue(((RegisterRestActionsResponse) response).getResponse().contains(actionsList.toString()));
    }

    public void testHandleRegisterRestActionsRequestWithInvalidMethod() throws Exception {

        Path extensionDir = createTempDir();

        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        extensionsOrchestrator.initializeServicesAndRestHandler(restController, transportService, clusterService);
        String uniqueIdStr = "uniqueid1";
        List<String> actionsList = List.of("FOO /foo", "PUT /bar", "POST /baz");
        RegisterRestActionsRequest registerActionsRequest = new RegisterRestActionsRequest(uniqueIdStr, actionsList);
        expectThrows(
            IllegalArgumentException.class,
            () -> extensionsOrchestrator.restActionsRequestHandler.handleRegisterRestActionsRequest(registerActionsRequest)
        );
    }

    public void testHandleRegisterRestActionsRequestWithInvalidUri() throws Exception {

        Path extensionDir = createTempDir();

        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        extensionsOrchestrator.initializeServicesAndRestHandler(restController, transportService, clusterService);
        String uniqueIdStr = "uniqueid1";
        List<String> actionsList = List.of("GET", "PUT /bar", "POST /baz");
        RegisterRestActionsRequest registerActionsRequest = new RegisterRestActionsRequest(uniqueIdStr, actionsList);
        expectThrows(
            IllegalArgumentException.class,
            () -> extensionsOrchestrator.restActionsRequestHandler.handleRegisterRestActionsRequest(registerActionsRequest)
        );
    }

    public void testHandleExtensionRequest() throws Exception {

        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        extensionsOrchestrator.initializeServicesAndRestHandler(restController, transportService, clusterService);
        ExtensionRequest clusterStateRequest = new ExtensionRequest(ExtensionsOrchestrator.RequestType.REQUEST_EXTENSION_CLUSTER_STATE);
        assertEquals(ClusterStateResponse.class, extensionsOrchestrator.handleExtensionRequest(clusterStateRequest).getClass());

        ExtensionRequest clusterSettingRequest = new ExtensionRequest(
            ExtensionsOrchestrator.RequestType.REQUEST_EXTENSION_CLUSTER_SETTINGS
        );
        assertEquals(ClusterSettingsResponse.class, extensionsOrchestrator.handleExtensionRequest(clusterSettingRequest).getClass());

        ExtensionRequest localNodeRequest = new ExtensionRequest(ExtensionsOrchestrator.RequestType.REQUEST_EXTENSION_LOCAL_NODE);
        assertEquals(LocalNodeResponse.class, extensionsOrchestrator.handleExtensionRequest(localNodeRequest).getClass());

        ExtensionRequest exceptionRequest = new ExtensionRequest(ExtensionsOrchestrator.RequestType.GET_SETTINGS);
        Exception exception = expectThrows(Exception.class, () -> extensionsOrchestrator.handleExtensionRequest(exceptionRequest));
        assertEquals("Handler not present for the provided request", exception.getMessage());
    }

    public void testRegisterHandler() throws Exception {

        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        TransportService mockTransportService = spy(
            new TransportService(
                Settings.EMPTY,
                mock(Transport.class),
                null,
                TransportService.NOOP_TRANSPORT_INTERCEPTOR,
                x -> null,
                null,
                Collections.emptySet()
            )
        );

        extensionsOrchestrator.initializeServicesAndRestHandler(restController, mockTransportService, clusterService);
        verify(mockTransportService, times(5)).registerRequestHandler(anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any());
    }

    private static class Example implements NamedWriteable {
        public static final String INVALID_NAME = "invalid_name";
        public static final String NAME = "example";
        private final String message;

        Example(String message) {
            this.message = message;
        }

        Example(StreamInput in) throws IOException {
            this.message = in.readString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(message);
        }

        @Override
        public String getWriteableName() {
            return NAME;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Example that = (Example) o;
            return Objects.equals(message, that.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(message);
        }
    }

    public void testGetNamedWriteables() throws Exception {
        Files.write(extensionDir.resolve("extensions.yml"), extensionsYmlLines, StandardCharsets.UTF_8);
        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);
        transportService.start();
        transportService.acceptIncomingRequests();
        extensionsOrchestrator.initializeServicesAndRestHandler(restController, transportService, clusterService);

        try (
            MockLogAppender mockLogAppender = MockLogAppender.createForLoggers(
                LogManager.getLogger(NamedWriteableRegistryResponseHandler.class)
            )
        ) {

            mockLogAppender.addExpectation(
                new MockLogAppender.SeenEventExpectation(
                    "OpenSearchRequest Failure",
                    "org.opensearch.extensions.NamedWriteableRegistryResponseHandler",
                    Level.ERROR,
                    "OpenSearchRequest failed"
                )
            );
            List<DiscoveryExtension> extensionsList = new ArrayList<>(extensionsOrchestrator.extensionIdMap.values());
            extensionsOrchestrator.namedWriteableRegistry = new ExtensionNamedWriteableRegistry(extensionsList, transportService);
            extensionsOrchestrator.namedWriteableRegistry.getNamedWriteables();
            mockLogAppender.assertAllExpectationsMatched();
        }
    }

    public void testNamedWriteableRegistryResponseHandler() throws Exception {
        Files.write(extensionDir.resolve("extensions.yml"), extensionsYmlLines, StandardCharsets.UTF_8);
        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);
        transportService.start();
        transportService.acceptIncomingRequests();
        extensionsOrchestrator.initializeServicesAndRestHandler(restController, transportService, clusterService);

        List<DiscoveryExtension> extensionsList = new ArrayList<>(extensionsOrchestrator.extensionIdMap.values());
        DiscoveryNode extensionNode = extensionsList.get(0);
        String requestType = ExtensionsOrchestrator.REQUEST_OPENSEARCH_NAMED_WRITEABLE_REGISTRY;

        // Create response to pass to response handler
        Map<String, Class> responseRegistry = new HashMap<>();
        responseRegistry.put(Example.NAME, Example.class);
        NamedWriteableRegistryResponse response = new NamedWriteableRegistryResponse(responseRegistry);

        NamedWriteableRegistryResponseHandler responseHandler = new NamedWriteableRegistryResponseHandler(
            extensionNode,
            transportService,
            requestType
        );
        responseHandler.handleResponse(response);

        // Ensure that response entries have been processed correctly into their respective maps
        Map<DiscoveryNode, Map<Class, Map<String, ExtensionReader>>> extensionsRegistry = responseHandler.getExtensionRegistry();
        assertEquals(extensionsRegistry.size(), 1);

        Map<Class, Map<String, ExtensionReader>> categoryMap = extensionsRegistry.get(extensionNode);
        assertEquals(categoryMap.size(), 1);

        Map<String, ExtensionReader> readerMap = categoryMap.get(Example.class);
        assertEquals(readerMap.size(), 1);

        ExtensionReader callback = readerMap.get(Example.NAME);
        assertNotNull(callback);
    }

    public void testGetExtensionReader() throws IOException {
        Files.write(extensionDir.resolve("extensions.yml"), extensionsYmlLines, StandardCharsets.UTF_8);
        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        extensionsOrchestrator.namedWriteableRegistry = spy(
            new ExtensionNamedWriteableRegistry(extensionsOrchestrator.extensionsInitializedList, transportService)
        );

        Exception e = expectThrows(
            Exception.class,
            () -> extensionsOrchestrator.namedWriteableRegistry.getExtensionReader(Example.class, Example.NAME)
        );
        assertEquals(e.getMessage(), "Unknown NamedWriteable [" + Example.class.getName() + "][" + Example.NAME + "]");
        verify(extensionsOrchestrator.namedWriteableRegistry, times(1)).getExtensionReader(any(), any());
    }

    public void testParseNamedWriteables() throws Exception {
        Files.write(extensionDir.resolve("extensions.yml"), extensionsYmlLines, StandardCharsets.UTF_8);
        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);
        transportService.start();
        transportService.acceptIncomingRequests();
        extensionsOrchestrator.initializeServicesAndRestHandler(restController, transportService, clusterService);

        String requestType = ExtensionsOrchestrator.REQUEST_OPENSEARCH_PARSE_NAMED_WRITEABLE;
        List<DiscoveryExtension> extensionsList = new ArrayList<>(extensionsOrchestrator.extensionIdMap.values());
        DiscoveryNode extensionNode = extensionsList.get(0);
        Class categoryClass = Example.class;

        // convert context into an input stream then stream input for mock
        byte[] context = new byte[0];
        InputStream inputStream = new ByteArrayInputStream(context);
        StreamInput in = new InputStreamStreamInput(inputStream);

        try (
            MockLogAppender mockLogAppender = MockLogAppender.createForLoggers(
                LogManager.getLogger(NamedWriteableRegistryParseResponseHandler.class)
            )
        ) {

            mockLogAppender.addExpectation(
                new MockLogAppender.SeenEventExpectation(
                    "NamedWriteableRegistryParseRequest Failure",
                    "org.opensearch.extensions.NamedWriteableRegistryParseResponseHandler",
                    Level.ERROR,
                    "NamedWriteableRegistryParseRequest failed"
                )
            );

            NamedWriteableRegistryResponseHandler responseHandler = new NamedWriteableRegistryResponseHandler(
                extensionNode,
                transportService,
                requestType
            );
            responseHandler.parseNamedWriteable(extensionNode, categoryClass, in);
            mockLogAppender.assertAllExpectationsMatched();
        }
    }

    public void testOnIndexModule() throws Exception {
        Files.write(extensionDir.resolve("extensions.yml"), extensionsYmlLines, StandardCharsets.UTF_8);

        ExtensionsOrchestrator extensionsOrchestrator = new ExtensionsOrchestrator(settings, extensionDir);

        transportService.start();
        transportService.acceptIncomingRequests();
        extensionsOrchestrator.initializeServicesAndRestHandler(restController, transportService, clusterService);

        Environment environment = TestEnvironment.newEnvironment(settings);
        AnalysisRegistry emptyAnalysisRegistry = new AnalysisRegistry(
            environment,
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap()
        );

        IndexSettings indexSettings = IndexSettingsModule.newIndexSettings("test_index", settings);
        IndexModule indexModule = new IndexModule(
            indexSettings,
            emptyAnalysisRegistry,
            new InternalEngineFactory(),
            new EngineConfigFactory(indexSettings),
            Collections.emptyMap(),
            () -> true,
            new IndexNameExpressionResolver(new ThreadContext(Settings.EMPTY)),
            Collections.emptyMap()
        );

        try (MockLogAppender mockLogAppender = MockLogAppender.createForLoggers(LogManager.getLogger(ExtensionsOrchestrator.class))) {

            mockLogAppender.addExpectation(
                new MockLogAppender.SeenEventExpectation(
                    "IndicesModuleRequest Failure",
                    "org.opensearch.extensions.ExtensionsOrchestrator",
                    Level.ERROR,
                    "IndicesModuleRequest failed"
                )
            );

            extensionsOrchestrator.onIndexModule(indexModule);
            mockLogAppender.assertAllExpectationsMatched();
        }
    }
}