package com.xxl.job.core.handler.http;

import com.sun.net.httpserver.HttpServer;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.log.XxlJobFileAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class HttpJobHandlerTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastToken = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeAll
    static void startServer(@TempDir Path tempDir) throws IOException {
        XxlJobFileAppender.initLogPath(tempDir.toFile().getPath());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/ok", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Admin-Token"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.createContext("/fail", exchange -> {
            byte[] resp = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static XxlJobContext runWithParam(HttpJobHandler handler, String param) throws Exception {
        XxlJobContext ctx = new XxlJobContext(1L, param, 1L, System.currentTimeMillis(),
                XxlJobFileAppender.getLogPath() + "/http-job-test.log", 0, 1);
        XxlJobContext.setXxlJobContext(ctx);
        handler.execute();
        return ctx;
    }

    @Test
    void success_when_2xx_and_headers_resolved_from_system_properties() throws Exception {
        System.setProperty("TEST_ADMIN_TOKEN", "secret-123");
        String param = "{\"url\":\"http://127.0.0.1:" + port + "/ok\",\"method\":\"POST\"," +
                "\"headers\":{\"X-Admin-Token\":\"${TEST_ADMIN_TOKEN}\"},\"data\":\"{\\\"a\\\":1}\",\"timeout\":5}";

        XxlJobContext ctx = runWithParam(new HttpJobHandler(), param);

        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, ctx.getHandleCode(), ctx.getHandleMsg());
        assertEquals("POST", lastMethod.get());
        assertEquals("secret-123", lastToken.get(), "placeholder must be resolved on the executor side");
        assertEquals("{\"a\":1}", lastBody.get());
    }

    @Test
    void fail_when_non_2xx() throws Exception {
        String param = "{\"url\":\"http://127.0.0.1:" + port + "/fail\",\"method\":\"GET\",\"timeout\":5}";

        XxlJobContext ctx = runWithParam(new HttpJobHandler(), param);

        assertEquals(XxlJobContext.HANDLE_CODE_FAIL, ctx.getHandleCode());
        assertTrue(ctx.getHandleMsg().contains("503"), ctx.getHandleMsg());
    }

    @Test
    void fail_when_placeholder_missing_and_target_not_called() throws Exception {
        lastMethod.set(null);
        String param = "{\"url\":\"http://127.0.0.1:" + port + "/ok\",\"headers\":{\"X-Admin-Token\":\"${PH_DEFINITELY_MISSING}\"}}";

        XxlJobContext ctx = runWithParam(new HttpJobHandler(), param);

        assertEquals(XxlJobContext.HANDLE_CODE_FAIL, ctx.getHandleCode());
        assertTrue(ctx.getHandleMsg().contains("PH_DEFINITELY_MISSING"), ctx.getHandleMsg());
        assertNull(lastMethod.get(), "target must not be called when a credential placeholder is unresolved");
    }

    @Test
    void fail_when_host_not_in_allow_list() throws Exception {
        HttpJobHandler handler = new HttpJobHandler(List.of("api.example.com", ".sentino.jp"));
        String param = "{\"url\":\"http://127.0.0.1:" + port + "/ok\",\"method\":\"GET\"}";

        XxlJobContext ctx = runWithParam(handler, param);

        assertEquals(XxlJobContext.HANDLE_CODE_FAIL, ctx.getHandleCode());
        assertTrue(ctx.getHandleMsg().contains("not allowed"), ctx.getHandleMsg());
    }

    @Test
    void allow_list_matching_rules() {
        HttpJobHandler handler = new HttpJobHandler(List.of("api.example.com", ".sentino.jp", "10.0.1.34:9082", "https://coucou.fun/api/"));

        assertTrue(handler.isAllowed("https://api.example.com/x"));
        assertFalse(handler.isAllowed("https://evil.example.com/x"));
        assertTrue(handler.isAllowed("https://api-coucou.sentino.jp/x"));
        assertTrue(handler.isAllowed("https://sentino.jp/x"));
        assertTrue(handler.isAllowed("http://10.0.1.34:9082/health"));
        assertFalse(handler.isAllowed("http://10.0.1.34:9088/health"));
        assertTrue(handler.isAllowed("https://coucou.fun/api/reconcile"));
        assertFalse(handler.isAllowed("https://coucou.fun/other"));
        assertTrue(new HttpJobHandler().isAllowed("http://anything/"), "empty allow list allows all");
    }

    @Test
    void param_validation() {
        assertNull(HttpJobHandler.validParam("{\"url\":\"https://a.b/c\"}"));
        assertNotNull(HttpJobHandler.validParam(""));
        assertNotNull(HttpJobHandler.validParam("not json"));
        assertNotNull(HttpJobHandler.validParam("{\"method\":\"POST\"}"));
        assertNotNull(HttpJobHandler.validParam("{\"url\":\"ftp://a.b/c\"}"));
        assertNotNull(HttpJobHandler.validParam("{\"url\":\"https://a.b/c\",\"method\":\"PATCHX\"}"));
        assertNotNull(HttpJobHandler.validParam("{\"url\":\"https://a.b/c\",\"timeout\":99999}"));
        assertNull(HttpJobHandler.validParam("{\"url\":\"${BASE_URL}/c\"}"), "placeholder urls are validated on the executor");

        HttpJobParam p = HttpJobHandler.parseParam("{\"url\":\"https://a.b/c\"}");
        assertEquals("POST", p.getMethod());
        assertEquals(HttpJobHandler.DEFAULT_TIMEOUT_SECONDS, p.getTimeout());
    }

    @Test
    void placeholder_resolution() {
        System.setProperty("PH_ONE", "1");
        assertEquals("v=1", HttpJobHandler.resolvePlaceholders("v=${PH_ONE}"));
        assertThrows(IllegalStateException.class, () -> HttpJobHandler.resolvePlaceholders("v=${PH_MISSING}"));
        assertEquals("plain", HttpJobHandler.resolvePlaceholders("plain"));
        assertEquals(Map.of("k", "1"), HttpJobHandler.resolvePlaceholders(Map.of("k", "${PH_ONE}")));
    }

}
