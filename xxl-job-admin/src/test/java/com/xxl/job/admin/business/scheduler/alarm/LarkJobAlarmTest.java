package com.xxl.job.admin.business.scheduler.alarm;

import com.sun.net.httpserver.HttpServer;
import com.xxl.job.admin.business.model.XxlJobInfo;
import com.xxl.job.admin.business.model.XxlJobLog;
import com.xxl.job.admin.business.scheduler.alarm.impl.LarkJobAlarm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class LarkJobAlarmTest {

    private static HttpServer server;
    private static String base;
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicInteger calls = new AtomicInteger();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/ok", ex -> reply(ex, "{\"code\":0,\"msg\":\"success\"}"));
        server.createContext("/rejected", ex -> reply(ex, "{\"code\":19021,\"msg\":\"sign match fail or timestamp is not within one hour from current time\"}"));
        server.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String json) throws IOException {
        calls.incrementAndGet();
        lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);   // 飞书失败也返回 200
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static XxlJobInfo job(int id) {
        XxlJobInfo info = new XxlJobInfo();
        info.setId(id);
        info.setName("Python HelloWorld 每10秒");
        info.setJobGroup(5);
        info.setExecutorHandler("httpJobHandler");
        return info;
    }

    private static XxlJobLog failedLog(long id) {
        XxlJobLog log = new XxlJobLog();
        log.setId(id);
        log.setTriggerTime(new Date());
        log.setTriggerCode(200);
        log.setTriggerMsg("任务触发类型：Cron触发<br>调度机器：10.0.0.1<br><span style=\"color:#00c0ef;\" > >>>触发调度<<< </span>");
        log.setHandleCode(500);
        log.setHandleMsg("http status 503");
        log.setExecutorAddress("http://10.0.0.2:9997/");
        return log;
    }

    @Test
    void not_configured_is_noop_and_counts_as_success() {
        LarkJobAlarm alarm = new LarkJobAlarm("", "", "prod", 300, "");
        assertFalse(alarm.isConfigured());
        assertTrue(alarm.doAlarm(job(1), failedLog(1)), "unconfigured channel must not turn the overall alarm status into failure");
    }

    @Test
    void sends_signed_text_message_and_reads_body_code() throws Exception {
        int before = calls.get();
        LarkJobAlarm alarm = new LarkJobAlarm(base + "/ok", "s3cret", "stage", 300, "http://xxl.example.com/");

        assertTrue(alarm.doAlarm(job(2), failedLog(42)));
        assertEquals(before + 1, calls.get());

        String body = lastBody.get();
        assertTrue(body.contains("\"msg_type\":\"text\""), body);
        assertTrue(body.contains("\"timestamp\""), body);
        assertTrue(body.contains("\"sign\""), body);
        assertTrue(body.contains("[stage]"), body);
        assertTrue(body.contains("httpJobHandler"), body);
        assertTrue(body.contains("500 http status 503"), body);
        assertTrue(body.contains("http://xxl.example.com/joblog/logDetailPage?id=42"), body);
        assertFalse(body.contains("<br>"), "html tags must be stripped: " + body);
        assertFalse(body.contains("<span"), "html tags must be stripped: " + body);
    }

    @Test
    void http_200_with_nonzero_code_is_a_failure() {
        LarkJobAlarm alarm = new LarkJobAlarm(base + "/rejected", "", "", 300, "");
        assertFalse(alarm.doAlarm(job(3), failedLog(3)));
    }

    @Test
    void same_job_is_suppressed_within_window_and_count_is_carried() {
        int before = calls.get();
        LarkJobAlarm alarm = new LarkJobAlarm(base + "/ok", "", "", 300, "");

        assertTrue(alarm.doAlarm(job(4), failedLog(1)));   // sent
        assertTrue(alarm.doAlarm(job(4), failedLog(2)));   // suppressed, still "success"
        assertTrue(alarm.doAlarm(job(4), failedLog(3)));   // suppressed
        assertTrue(alarm.doAlarm(job(5), failedLog(4)));   // different job, sent
        assertEquals(before + 2, calls.get());

        LarkJobAlarm noWindow = new LarkJobAlarm(base + "/ok", "", "", 0, "");
        assertTrue(noWindow.doAlarm(job(6), failedLog(1)));
        assertTrue(noWindow.doAlarm(job(6), failedLog(2)));
        assertEquals(before + 4, calls.get());
    }

    @Test
    void sign_matches_feishu_algorithm() throws Exception {
        // HMAC-SHA256 over an empty payload with key "{timestamp}\n{secret}", Base64 encoded
        assertEquals("VIS10b0EBvzzSdFnuk4tznEmK5wHaruvf/WnViv2yR4=", LarkJobAlarm.sign("1700000000", "abc"));
    }

    @Test
    void parse_code_and_strip_html() {
        assertEquals(0, LarkJobAlarm.parseCode("{\"code\":0}"));
        assertEquals(0, LarkJobAlarm.parseCode("{\"StatusCode\":0,\"StatusMessage\":\"success\"}"));
        assertEquals(11232, LarkJobAlarm.parseCode("{\"code\":11232,\"msg\":\"too many request\"}"));
        assertEquals(0, LarkJobAlarm.parseCode("not json"));
        assertEquals("a\nb c", LarkJobAlarm.stripHtml("a<br>b <span style=\"x\">c</span>"));
    }

}
