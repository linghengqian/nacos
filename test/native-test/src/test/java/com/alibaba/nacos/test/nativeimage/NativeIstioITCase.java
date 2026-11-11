/*
 * Copyright 1999-2026 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.test.nativeimage;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import istio.mcp.v1alpha1.Mcp;
import istio.mcp.v1alpha1.ResourceOuterClass;
import istio.mcp.v1alpha1.ResourceSourceGrpc;
import istio.networking.v1alpha3.ServiceEntryOuterClass;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Istio adaptor: an MCP and an xDS server on one more port, which turn the services of the
 * Naming registry into ServiceEntry, cluster, endpoint, listener and route resources, and which
 * read the VirtualService and DestinationRule configurations that are published with an Istio tag.
 * Whether the adaptor is part of a Native executable is a structural choice of the build
 * ({@code nacos.extension.naming.istio.enabled}, which a package filter reads while the
 * application contexts are prepared). The Server here also switches its port on and repeats the
 * build switch, which the ordinary Server needs.
 *
 * <p>The adaptor logs the responses that it pushes at debug level. The logging configuration of the
 * distribution sets that level for the ordinary Server, and the AOT application and the Native
 * executable do not read that configuration, so the test sets the level itself. Printing a message
 * reads every accessor of the generated messages by reflection, which is what the test exercises.
 *
 * @author Nacos
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NativeIstioITCase {

    private static final String GROUP = "DEFAULT_GROUP";

    private static final String TYPE_PREFIX = "type.googleapis.com/";

    private static final String SERVICE_ENTRY_COLLECTION =
            "istio/networking/v1alpha3/serviceentries";

    private static final String SERVICE_ENTRY_TYPE = "networking.istio.io/v1alpha3/ServiceEntry";

    private static final String CLUSTER_TYPE = TYPE_PREFIX + "envoy.config.cluster.v3.Cluster";

    private static final String ENDPOINT_TYPE =
            TYPE_PREFIX + "envoy.config.endpoint.v3.ClusterLoadAssignment";

    private static final String LISTENER_TYPE = TYPE_PREFIX + "envoy.config.listener.v3.Listener";

    private static final String ROUTE_TYPE =
            TYPE_PREFIX + "envoy.config.route.v3.RouteConfiguration";

    private static final String INSTANCE_IP = "127.0.0.1";

    private static final int INSTANCE_PORT = 18080;

    private final NativeHttp http = new NativeHttp();

    private NativeEnvironment environment;

    private ManagedChannel channel;

    private String api;

    private String token;

    @BeforeAll
    void startEnvironment() {
        int istioPort = NativeServer.freePort();
        environment = NativeEnvironment.builder("istio")
                .property("nacos.extension.naming.istio.enabled", "true")
                .property("nacos.istio.mcp.server.enabled", "true")
                .property("nacos.istio.mcp.server.port", String.valueOf(istioPort))
                .property("logging.level.com.alibaba.nacos.istio.main", "DEBUG").build().start();
        api = environment.server().apiUrl() + "/nacos/v3";
        token = environment.identities().adminToken();
        channel = ManagedChannelBuilder.forAddress(INSTANCE_IP, istioPort).usePlaintext().build();
        awaitReady();
    }

    @AfterAll
    void stopEnvironment() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (environment != null) {
            environment.close();
        }
    }

    @Test
    void mcpStreamServesTheServiceEntriesAndPushesChanges() throws Throwable {
        tolerateCutStreams(this::mcpExchange);
    }

    private void mcpExchange() throws Exception {
        String first = registerService();
        Inbox<Mcp.Resources> inbox = new Inbox<>(resources -> resources.getCollection() + " "
                + resources.getResourcesList().stream()
                .map(resource -> resource.getMetadata().getName()).toList());
        StreamObserver<Mcp.RequestResources> requests =
                ResourceSourceGrpc.newStub(channel).establishResourceStream(inbox);
        try {
            Mcp.Resources initial = inbox.retrying("the service entries of " + first,
                    () -> requests.onNext(mcpRequest("native-mcp", "")),
                    resources -> mentions(resources, first));
            assertEquals(SERVICE_ENTRY_COLLECTION, initial.getCollection());
            assertTrue(initial.getResourcesList().stream().anyMatch(resource ->
                    resource.getBody().getTypeUrl().equals(TYPE_PREFIX
                            + "istio.networking.v1alpha3.ServiceEntry")));
            ResourceOuterClass.Resource resource = find(initial, first);
            ServiceEntryOuterClass.ServiceEntry entry =
                    ServiceEntryOuterClass.ServiceEntry.parseFrom(resource.getBody().getValue());
            assertTrue(entry.getEndpointsList().stream()
                    .anyMatch(endpoint -> INSTANCE_IP.equals(endpoint.getAddress())), entry.toString());
            requests.onNext(mcpRequest("native-mcp", initial.getNonce()));

            String second = registerService();
            Mcp.Resources pushed = inbox.await("the push for " + second,
                    resources -> mentions(resources, second));
            assertEquals(SERVICE_ENTRY_COLLECTION, pushed.getCollection());
            requests.onNext(mcpRequest("native-mcp", pushed.getNonce()));
        } finally {
            requests.onCompleted();
        }
    }

    @Test
    void adsStreamServesTheXdsResources() throws Throwable {
        tolerateCutStreams(this::adsExchange);
    }

    private void adsExchange() throws Exception {
        String service = registerService();
        Inbox<DiscoveryResponse> inbox = new Inbox<>(
                response -> response.getTypeUrl() + " x" + response.getResourcesCount());
        StreamObserver<DiscoveryRequest> requests =
                AggregatedDiscoveryServiceGrpc.newStub(channel).streamAggregatedResources(inbox);
        try {
            // The snapshot that answers a request may not hold a service that was registered a
            // moment ago. The Server pushes the change to the types that a connection watches, so
            // the test waits for the response that mentions the service, as a proxy would.
            Cluster cluster = clusterOf(ask(requests, inbox, CLUSTER_TYPE,
                    response -> clusterOf(response, service) != null), service);
            assertEquals(Cluster.DiscoveryType.EDS, cluster.getType());

            ClusterLoadAssignment assignment = assignmentOf(ask(requests, inbox, ENDPOINT_TYPE,
                    response -> assignmentOf(response, service) != null, cluster.getName()),
                    service);
            assertTrue(assignment.getEndpointsList().stream()
                    .flatMap(locality -> locality.getLbEndpointsList().stream())
                    .anyMatch(endpoint -> {
                        var address = endpoint.getEndpoint().getAddress().getSocketAddress();
                        return INSTANCE_IP.equals(address.getAddress())
                                && address.getPortValue() == INSTANCE_PORT;
                    }), "the instance is not an endpoint of " + assignment.getClusterName());

            DiscoveryResponse listeners = ask(requests, inbox, LISTENER_TYPE);
            assertTrue(listeners.getResourcesCount() > 0);
            assertTrue(listeners.getResources(0).is(Listener.class));

            DiscoveryResponse routes = ask(requests, inbox, ROUTE_TYPE, "native_route_config");
            RouteConfiguration route = unpack(routes.getResources(0), RouteConfiguration.class);
            assertEquals("native_route_config", route.getName());
            assertEquals("native", route.getVirtualHosts(0).getName());

            ask(requests, inbox, SERVICE_ENTRY_TYPE,
                    response -> entryOf(response, service) != null);
        } finally {
            requests.onCompleted();
        }
    }

    @Test
    void virtualServiceIsPushedAsRouteConfiguration() throws Exception {
        String service = registerService();
        String name = "native-vs-" + UUID.randomUUID();
        Inbox<DiscoveryResponse> inbox = new Inbox<>(
                response -> response.getTypeUrl() + " x" + response.getResourcesCount());
        StreamObserver<DiscoveryRequest> requests =
                AggregatedDiscoveryServiceGrpc.newStub(channel).streamAggregatedResources(inbox);
        try {
            // Any request makes the connection known to the Server, which pushes the routes of a
            // virtual service to every connection.
            ask(requests, inbox, CLUSTER_TYPE);
            publishIstioConfig("virtual-service", name, virtualService(name, service));
            DiscoveryResponse pushed = inbox.await("the routes of " + name,
                    response -> ROUTE_TYPE.equals(response.getTypeUrl())
                            && response.getResourcesList().stream().anyMatch(any ->
                            unpack(any, RouteConfiguration.class).getName()
                                    .equals(name + "_route_config")));
            RouteConfiguration route = pushed.getResourcesList().stream()
                    .map(any -> unpack(any, RouteConfiguration.class))
                    .filter(candidate -> candidate.getName().equals(name + "_route_config"))
                    .findFirst().orElseThrow();
            List<Route> routes = route.getVirtualHosts(0).getRoutesList();
            assertEquals(4, routes.size());

            Route primary = routes.get(0);
            assertEquals("primary", primary.getName());
            assertEquals(RouteMatch.PathSpecifierCase.PREFIX, primary.getMatch().getPathSpecifierCase());
            assertEquals("/api", primary.getMatch().getPrefix());
            assertEquals("/v2", primary.getRoute().getPrefixRewrite());
            assertTrue(primary.getRoute().getCluster().contains(service), primary.getRoute().getCluster());

            assertEquals(RouteMatch.PathSpecifierCase.PATH,
                    routes.get(1).getMatch().getPathSpecifierCase());
            assertEquals("/health", routes.get(1).getMatch().getPath());
            assertEquals(RouteMatch.PathSpecifierCase.SAFE_REGEX,
                    routes.get(2).getMatch().getPathSpecifierCase());
            assertEquals("/items/[0-9]+", routes.get(2).getMatch().getSafeRegex().getRegex());

            Route moved = routes.get(3);
            assertEquals(Route.ActionCase.REDIRECT, moved.getActionCase());
            assertEquals("/new", moved.getRedirect().getPathRedirect());
            assertEquals("example.org", moved.getRedirect().getHostRedirect());
        } finally {
            requests.onCompleted();
        }
    }

    @Test
    void destinationRuleIsParsed() throws Exception {
        String service = registerService();
        String name = "native-dr-" + UUID.randomUUID();
        publishIstioConfig("destination-rule", name, destinationRule(name, service));
        assertTrue(environment.server().awaitLog(
                "Configuration Content was successfully parsed as DestinationRule",
                NativeTestSettings.scaled(Duration.ofSeconds(30))),
                "the Server did not parse the destination rule");
    }

    /**
     * Runs an exchange again, with a service of its own, when the Server cut its stream. The
     * generators of the ServiceEntry resources keep their working list in a field of a shared
     * object, so two requests that are handled at the same time, or a request and a push, can fail
     * with a ConcurrentModificationException, which the client sees as {@code UNKNOWN: Application
     * error processing RPC}. That is a defect of the adaptor (alibaba/nacos#16022), which the ordinary
     * Server has as well, and no property of the Native executable.
     */
    private static void tolerateCutStreams(Executable exchange) throws Throwable {
        StreamCut last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                exchange.execute();
                return;
            } catch (StreamCut e) {
                last = e;
            }
        }
        throw last;
    }

    private void awaitReady() {
        long deadline = System.nanoTime()
                + NativeTestSettings.scaled(Duration.ofSeconds(60)).toNanos();
        while (channel.getState(true) != ConnectivityState.READY) {
            if (System.nanoTime() > deadline) {
                fail("the Istio port does not accept connections");
            }
            sleep(500);
        }
    }

    /**
     * Registers a service of its own with two instances. The instances are ephemeral and expire
     * without a heartbeat, so every test registers its own service. The Server builds its Istio
     * view of a service when the registration event arrives, which can be before the instance is in
     * the index that the view reads (alibaba/nacos#16023), and refreshes it with the next event. The
     * second instance is that event, and it finds the first one.
     */
    private String registerService() {
        String service = "native-istio-" + UUID.randomUUID();
        for (int port : new int[] {INSTANCE_PORT, INSTANCE_PORT + 1}) {
            NativeHttp.Response registered = http.form("POST", api + "/client/ns/instance", token,
                    Map.of("serviceName", service, "ip", INSTANCE_IP, "port",
                            String.valueOf(port)));
            assertTrue(registered.ok(), registered.describe());
            NativeHttp.Response listed = eventually(() -> http.get(api
                            + "/client/ns/instance/list?serviceName=" + service, token),
                    response -> response.ok()
                            && response.body().contains(String.valueOf(port)));
            assertTrue(listed.ok(), listed.describe());
        }
        return service;
    }

    private void publishIstioConfig(String tag, String dataId, String content) {
        NativeHttp.Response published = http.form("POST", api + "/admin/cs/config", token,
                Map.of("dataId", dataId, "groupName", GROUP, "content", content, "type", "yaml",
                        "configTags", tag));
        assertTrue(published.ok(), published.describe());
    }

    private static DiscoveryResponse ask(StreamObserver<DiscoveryRequest> requests,
            Inbox<DiscoveryResponse> inbox, String type, String... names) {
        return ask(requests, inbox, type, response -> true, names);
    }

    /**
     * Sends the first request of a type, waits for the response that is wanted and answers it, as a
     * proxy does.
     */
    private static DiscoveryResponse ask(StreamObserver<DiscoveryRequest> requests,
            Inbox<DiscoveryResponse> inbox, String type, Predicate<DiscoveryResponse> wanted,
            String... names) {
        DiscoveryRequest.Builder request = DiscoveryRequest.newBuilder()
                .setNode(Node.newBuilder().setId("native-ads")).setTypeUrl(type);
        for (String name : names) {
            request.addResourceNames(name);
        }
        DiscoveryRequest first = request.build();
        DiscoveryResponse response = inbox.retrying("the response for " + type,
                () -> requests.onNext(first),
                candidate -> type.equals(candidate.getTypeUrl()) && wanted.test(candidate));
        requests.onNext(DiscoveryRequest.newBuilder().setNode(Node.newBuilder().setId("native-ads"))
                .setTypeUrl(type).setVersionInfo(response.getVersionInfo())
                .setResponseNonce(response.getNonce()).build());
        return response;
    }

    private static Cluster clusterOf(DiscoveryResponse response, String service) {
        return response.getResourcesList().stream().map(any -> unpack(any, Cluster.class))
                .filter(candidate -> candidate.getName().contains(service)).findFirst()
                .orElse(null);
    }

    private static ClusterLoadAssignment assignmentOf(DiscoveryResponse response,
            String service) {
        return response.getResourcesList().stream()
                .map(any -> unpack(any, ClusterLoadAssignment.class))
                .filter(candidate -> candidate.getClusterName().contains(service)).findFirst()
                .orElse(null);
    }

    private static ResourceOuterClass.Resource entryOf(DiscoveryResponse response,
            String service) {
        return response.getResourcesList().stream()
                .map(any -> unpack(any, ResourceOuterClass.Resource.class))
                .filter(resource -> resource.getMetadata().getName().contains(service))
                .findFirst().orElse(null);
    }

    private static Mcp.RequestResources mcpRequest(String node, String nonce) {
        return Mcp.RequestResources.newBuilder()
                .setSinkNode(Mcp.SinkNode.newBuilder().setId(node))
                .setCollection(SERVICE_ENTRY_COLLECTION).setResponseNonce(nonce).build();
    }

    private static boolean mentions(Mcp.Resources resources, String service) {
        return find(resources, service) != null;
    }

    private static ResourceOuterClass.Resource find(Mcp.Resources resources, String service) {
        return resources.getResourcesList().stream()
                .filter(resource -> resource.getMetadata().getName().contains(service))
                .findFirst().orElse(null);
    }

    private static <T extends com.google.protobuf.Message> T unpack(Any any, Class<T> type) {
        try {
            return any.unpack(type);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String virtualService(String name, String host) {
        return """
                apiVersion: networking.istio.io/v1alpha3
                kind: VirtualService
                metadata:
                  name: %1$s
                  namespace: default
                spec:
                  hosts:
                    - %2$s
                  http:
                    - name: primary
                      match:
                        - uri:
                            prefix: /api
                      rewrite:
                        uri: /v2
                      route:
                        - destination:
                            host: %2$s
                            subset: v1
                            port:
                              number: %3$d
                    - name: exact
                      match:
                        - uri:
                            exact: /health
                      route:
                        - destination:
                            host: %2$s
                    - name: pattern
                      match:
                        - uri:
                            regex: /items/[0-9]+
                      route:
                        - destination:
                            host: %2$s
                    - name: moved
                      match:
                        - uri:
                            prefix: /old
                      redirect:
                        uri: /new
                        authority: example.org
                """.formatted(name, host, INSTANCE_PORT);
    }

    private static String destinationRule(String name, String host) {
        return """
                apiVersion: networking.istio.io/v1alpha3
                kind: DestinationRule
                metadata:
                  name: %1$s
                  namespace: default
                spec:
                  host: %2$s
                  subsets:
                    - name: v1
                      labels:
                        version: v1
                """.formatted(name, host);
    }

    private static NativeHttp.Response eventually(Supplier<NativeHttp.Response> request,
            Predicate<NativeHttp.Response> done) {
        NativeHttp.Response response = request.get();
        for (int attempt = 0; attempt < 20 && !done.test(response); attempt++) {
            sleep(500);
            response = request.get();
        }
        return response;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * The Server ended a stream with an error.
     */
    private static final class StreamCut extends AssertionError {

        private StreamCut(String message) {
            super(message);
        }
    }

    /**
     * Collects what a stream delivers, so that a test waits for the message that it expects.
     */
    private static final class Inbox<T> implements StreamObserver<T> {

        private static final Duration RETRY = Duration.ofSeconds(3);

        private final BlockingQueue<T> messages = new LinkedBlockingQueue<>();

        private final List<String> seen = new CopyOnWriteArrayList<>();

        private final Function<T, String> describe;

        private volatile Throwable failure;

        private Inbox(Function<T, String> describe) {
            this.describe = describe;
        }

        @Override
        public void onNext(T value) {
            seen.add(describe.apply(value));
            messages.add(value);
        }

        @Override
        public void onError(Throwable error) {
            failure = error;
        }

        @Override
        public void onCompleted() {
        }

        T await(String what, Predicate<T> wanted) {
            T message = within(NativeTestSettings.scaled(Duration.ofSeconds(30)), wanted);
            return message != null ? message : fail(what + ": nothing matched; " + seen);
        }

        /**
         * Sends the request again until the wanted message arrives, as a proxy does when its
         * view is stale: the answer to an initial request comes from the current snapshot.
         */
        T retrying(String what, Runnable request, Predicate<T> wanted) {
            long deadline = System.nanoTime()
                    + NativeTestSettings.scaled(Duration.ofSeconds(30)).toNanos();
            while (System.nanoTime() < deadline) {
                request.run();
                T message = within(RETRY, wanted);
                if (message != null) {
                    return message;
                }
            }
            return fail(what + ": nothing matched; " + seen);
        }

        private T within(Duration timeout, Predicate<T> wanted) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    T message = messages.poll(200, TimeUnit.MILLISECONDS);
                    if (message != null && wanted.test(message)) {
                        return message;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                if (failure != null) {
                    throw new StreamCut("the stream failed: " + failure + "; " + seen);
                }
            }
            return null;
        }
    }
}
