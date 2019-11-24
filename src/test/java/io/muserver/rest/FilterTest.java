package io.muserver.rest;

import io.muserver.MuRequest;
import io.muserver.MuServer;
import io.muserver.Mutils;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.ServerUtils;

import javax.ws.rs.*;
import javax.ws.rs.container.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.NewCookie;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class FilterTest {

    private final List<String> received = new ArrayList<>();

    @Path("something")
    class TheWay {
        @POST
        public String itMoves() {
            return "Hello";
        }
    }

    @PreMatching
    class MethodChangingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            received.add("REQUEST " + requestContext);
            requestContext.setMethod("POST");
            requestContext.setRequestUri(URI.create("something"));
            requestContext.setProperty("a-property", "a-value");
        }
    }

    class LoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            received.add("REQUEST " + requestContext + " - " + requestContext.getProperty("a-property"));
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
            received.add("RESPONSE " + responseContext);
        }
    }


    @Test
    public void requestUrisAndMethodsCanBeChangedSoThatThingsCanMatch() throws IOException {
        LoggingFilter loggingFilter = new LoggingFilter();
        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(loggingFilter)
                    .addResponseFilter(loggingFilter)
                    .addRequestFilter(new MethodChangingFilter())
            ).start();

        try (Response resp = call(request().url(server.uri().resolve("/blah").toString()))) {
            assertThat(resp.body().string(), is("Hello"));
        }
        assertThat(received, contains(
            "REQUEST GET " + server.uri().resolve("/blah"),
            "REQUEST POST " + server.uri().resolve("/something") + " - a-value",
            "RESPONSE OK"
        ));
    }

    @Test
    public void requestContextPropertiesAreSharedWithRequestAttributes() throws IOException {

        @Path("/blah")
        class Blah {
            @GET
            public String hi(@Context MuRequest mur, @Context ContainerRequestContext crc) {
                MuRequest murequest = (MuRequest) mur.attribute("murequest");
                MuRequest murequest2 = (MuRequest) crc.getProperty("murequest");
                return "MUR: " + murequest.method() + " " + murequest.attribute("hello") + " "
                    + murequest.attribute("hello2") + " " + murequest.attribute("hello3")
                    + " CRC: " + murequest2.method() + " " + crc.getProperty("hello") + " "
                    + crc.getProperty("hello2") + " " + crc.getProperty("hello3");
            }
        }

        AtomicReference<Throwable> error = new AtomicReference<>();
        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler((request, response) -> {
                request.attribute("murequest", request);
                request.attribute("hello", "world");
                request.attribute("hello2", "world");
                return false;
            })
            .addHandler(
                restHandler()
                    .addRequestFilter(requestContext -> {
                        try {
                            try {
                                requestContext.getPropertyNames().add("shouldnothappen");
                                Assert.fail("Should not have worked");
                            } catch (UnsupportedOperationException e) {
                                // expected
                            }
                            assertThat(requestContext.getPropertyNames(), containsInAnyOrder("murequest", "hello", "hello2"));
                            requestContext.removeProperty("hello");
                            requestContext.setProperty("hello2", null);
                            requestContext.setProperty("hello3", "temp");
                            requestContext.setProperty("hello3", "hello3");
                        } catch (Throwable e) {
                            error.set(e);
                        }
                    })
                .addResource(new Blah())
            ).start();

        try (Response resp = call(request(server.uri().resolve("/blah")))) {
            assertThat(resp.body().string(), is("MUR: GET null null hello3 CRC: GET null null hello3"));
        }
        assertThat(error.get(), is(nullValue()));
    }

    @Test
    public void itWorksWithContextsToo() throws IOException {
        LoggingFilter loggingFilter = new LoggingFilter();
        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(context("in a context")
                .addHandler(
                    restHandler(new TheWay())
                        .addRequestFilter(new MethodChangingFilter())
                        .addRequestFilter(loggingFilter)
                )).start();
        try (Response resp = call(request().url(server.uri().resolve("/in%20a%20context/blah").toString()))) {
            assertThat(resp.body().string(), is("Hello"));
        }
        assertThat(received, contains(
            "REQUEST GET " + server.uri().resolve("/in%20a%20context/blah"),
            "REQUEST POST " + server.uri().resolve("/in%20a%20context/something") + " - a-value"
        ));
    }

    @Test
    public void theInputStreamCanBeSwappedOut() throws IOException {
        @Path("/echo")
        class Something {
            @POST
            public String echo(String body) {
                return body;
            }
        }

        @PreMatching
        class RequestInputChanger implements ContainerRequestFilter {
            public void filter(ContainerRequestContext requestContext) throws IOException {
                if (requestContext.hasEntity()) {
                    String originalText;
                    try (InputStream original = requestContext.getEntityStream()) {
                        ByteArrayOutputStream originalContent = new ByteArrayOutputStream();
                        Mutils.copy(original, originalContent, 8192);
                        originalText = originalContent.toString("UTF-8");
                    }
                    InputStream replacement = new ByteArrayInputStream(originalText.toUpperCase().getBytes("UTF-8"));
                    requestContext.setEntityStream(replacement);
                }
            }
        }

        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(
                restHandler(new Something())
                    .addRequestFilter(new RequestInputChanger())
            ).start();
        try (Response resp = call(request()
            .url(server.uri().resolve("/echo").toString())
            .post(new RequestBody() {
                public MediaType contentType() {
                    return MediaType.parse("text/plain");
                }

                public void writeTo(BufferedSink sink) throws IOException {
                    sink.writeUtf8("Hello there");
                }
            })
        )) {
            assertThat(resp.body().string(), is("HELLO THERE"));
        }
    }


    @Test
    public void requestsCanBeAborted() throws IOException {
        @Path("something")
        class TheWay {
            @GET
            public String itMoves() {
                return "Not called";
            }
        }
        @PreMatching
        class MethodChangingFilter implements ContainerRequestFilter {
            @Override
            public void filter(ContainerRequestContext requestContext) throws IOException {
                requestContext.abortWith(javax.ws.rs.core.Response.status(409).entity("Blocked!").build());
            }
        }
        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(new MethodChangingFilter())
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.code(), is(409));
            assertThat(resp.body().string(), is("Blocked!"));
        }
    }


    @Test
    public void ifExceptionIsThrownThenNormalExceptionHandlingHappens() throws IOException {
        @Path("something")
        class TheWay {
            @GET
            public String itMoves() {
                return "Not called";
            }
        }
        @PreMatching
        class MethodChangingFilter implements ContainerRequestFilter {
            @Override
            public void filter(ContainerRequestContext requestContext) throws IOException {
                throw new BadRequestException("Bad!!!");
            }
        }
        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(new MethodChangingFilter())
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), is("<h1>400 Bad Request</h1><p>Bad!!!</p>"));
        }
    }


    @Test
    public void responseFiltersCanChangeHeadersAndCookiesAndResponseCodesAndEntitiesEven() throws IOException {
        @Path("something")
        class TheWay {
            @GET
            @Produces("text/html")
            public javax.ws.rs.core.Response itMoves() {
                return javax.ws.rs.core.Response.status(200)
                    .header("My-Header", "was-lowercase")
                    .entity("a lowercase string")
                    .cookie(new NewCookie("my-cookie", "cooke-value"))
                    .build();
            }
        }


        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addResponseFilter(new ContainerResponseFilter() {
                        @Override
                        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
                            responseContext.setStatus(400);
                            responseContext.setEntity(12, new Annotation[0], javax.ws.rs.core.MediaType.TEXT_PLAIN_TYPE);
                            responseContext.getStringHeaders().putSingle("My-Header", responseContext.getHeaderString("My-Header").toUpperCase());
                            responseContext.getHeaders().put("My-Number", asList(1, 2, 3));
                        }
                    })
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.header("Content-Type"), is("text/plain;charset=utf-8"));
            assertThat(resp.body().string(), is("12"));
        }
    }

    @Test
    public void theContainerRequestContextCanBeAccessedFromRestMethods() throws IOException {
        @Path("something")
        class TheWay {
            @GET
            @Produces("text/plain")
            public String itMoves(@Context ContainerRequestContext reqContext) {
                return reqContext.getProperty("one") + " "
                    + reqContext.getProperty("two") + " " + reqContext.getProperty("three") + " " + reqContext.getProperty("four");
            }
        }

        MuServer server = ServerUtils.httpsServerForTest()
            .addHandler(
                restHandler(new TheWay())
                    .addRequestFilter(requestContext -> {
                        requestContext.setProperty("one", "oneandtwoprop");
                        requestContext.setProperty("two", requestContext.getProperty("one"));
                        requestContext.setProperty("three", "temp");
                    })
                    .addRequestFilter(requestContext -> {
                        requestContext.removeProperty("three");
                    })
            ).start();
        try (Response resp = call(request().url(server.uri().resolve("/something").toString()))) {
            assertThat(resp.body().string(), is("oneandtwoprop oneandtwoprop null null"));
        }
    }


}
