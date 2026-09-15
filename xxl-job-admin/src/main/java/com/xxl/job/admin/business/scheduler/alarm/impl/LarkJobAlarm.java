package com.xxl.job.admin.business.scheduler.alarm.impl;

import com.xxl.job.admin.business.model.XxlJobGroup;
import com.xxl.job.admin.business.model.XxlJobInfo;
import com.xxl.job.admin.business.model.XxlJobLog;
import com.xxl.job.admin.business.scheduler.alarm.JobAlarm;
import com.xxl.job.admin.business.scheduler.config.XxlJobAdminBootstrap;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.http.HttpTool;
import com.xxl.tool.http.http.HttpResponse;
import com.xxl.tool.http.http.enums.ContentType;
import com.xxl.tool.http.http.enums.Method;
import com.xxl.tool.json.GsonTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 飞书 / Lark 自定义机器人告警通道。
 *
 * <p>实现参考 coucou-server 的 FeishuAlertNotifier，沿用其三条经验：
 * <ol>
 *   <li>飞书<b>失败也返回 HTTP 200</b>，必须看响应体 {@code code != 0}（19021=签名/时间戳，11232=限流）；</li>
 *   <li>单机器人限流 100 次/分钟、5 次/秒，故按任务做<b>抑制窗口</b>：同一任务在窗口内只发一条，其余计数并在下次放行时说明；</li>
 *   <li>未配置 webhook 时降级为 no-op，且<b>返回成功</b>——JobAlarmer 要求所有通道都成功才把日志标记为"告警成功"，
 *       一个未启用的通道不应把邮件通道的成功变成失败。</li>
 * </ol>
 *
 * <p>配置项（application.properties，值走环境变量）：
 * <pre>
 * xxl.job.alarm.lark.webhook-url            群机器人地址，空则整个通道 no-op
 * xxl.job.alarm.lark.secret                 签名密钥，可选
 * xxl.job.alarm.lark.env                    环境标识（prod / stage），消息前缀
 * xxl.job.alarm.lark.suppress-window-seconds 抑制窗口秒数，默认 300
 * xxl.job.alarm.lark.admin-url              调度中心外网可达地址，用于拼执行日志链接，可选
 * </pre>
 *
 * @author sentino 2026-09-15
 */
@Component
public class LarkJobAlarm implements JobAlarm {
    private static final Logger logger = LoggerFactory.getLogger(LarkJobAlarm.class);

    private static final int HTTP_TIMEOUT_MILLIS = 5000;
    private static final int MSG_FIELD_LIMIT = 500;

    private final String webhookUrl;
    private final String secret;
    private final String env;
    private final Duration suppressWindow;
    private final String adminUrl;

    /** jobId → 抑制状态（上次发送时刻 + 期间被抑制的条数） */
    private final Map<Integer, Suppression> suppressions = new ConcurrentHashMap<>();

    private record Suppression(Instant lastSentAt, AtomicInteger suppressed) {}

    public LarkJobAlarm(@Value("${xxl.job.alarm.lark.webhook-url:}") String webhookUrl,
                        @Value("${xxl.job.alarm.lark.secret:}") String secret,
                        @Value("${xxl.job.alarm.lark.env:}") String env,
                        @Value("${xxl.job.alarm.lark.suppress-window-seconds:300}") long suppressWindowSeconds,
                        @Value("${xxl.job.alarm.lark.admin-url:}") String adminUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.env = env == null ? "" : env.trim();
        this.suppressWindow = Duration.ofSeconds(Math.max(0, suppressWindowSeconds));
        this.adminUrl = adminUrl == null ? "" : adminUrl.trim().replaceAll("/+$", "");
        if (this.webhookUrl.isBlank()) {
            logger.info(">>>>>>>>>>> xxl-job lark alarm: xxl.job.alarm.lark.webhook-url not configured, lark alarm disabled (no-op).");
        } else {
            logger.info(">>>>>>>>>>> xxl-job lark alarm enabled, env={}, suppressWindow={}s, signed={}", this.env, this.suppressWindow.getSeconds(), !this.secret.isBlank());
        }
    }

    public boolean isConfigured() {
        return !webhookUrl.isBlank();
    }

    // ---------------------- JobAlarm ----------------------

    @Override
    public boolean doAlarm(XxlJobInfo info, XxlJobLog jobLog) {
        if (!isConfigured() || info == null || jobLog == null) {
            return true;    // no-op counts as success, see class doc
        }
        try {
            int suppressed = claimSlot(info.getId());
            if (suppressed < 0) {
                logger.debug(">>>>>>>>>>> xxl-job lark alarm suppressed, jobId={}, logId={}", info.getId(), jobLog.getId());
                return true;
            }
            String text = render(info, jobLog, suppressed);
            boolean sent = send(text);
            if (sent) {
                logger.info(">>>>>>>>>>> xxl-job lark alarm sent, jobId={}, logId={}, suppressedBefore={}", info.getId(), jobLog.getId(), suppressed);
            }
            return sent;
        } catch (Exception e) {
            logger.warn(">>>>>>>>>>> xxl-job lark alarm send error, jobId={}, logId={}: {}", info.getId(), jobLog.getId(), e.toString());
            return false;
        }
    }

    // ---------------------- suppression ----------------------

    /**
     * @return >= 0 放行（值为本次之前被抑制的条数）；-1 抑制
     */
    int claimSlot(int jobId) {
        Instant now = Instant.now();
        Suppression prev = suppressions.get(jobId);
        if (prev != null && Duration.between(prev.lastSentAt(), now).compareTo(suppressWindow) < 0) {
            prev.suppressed().incrementAndGet();
            return -1;
        }
        int carried = prev == null ? 0 : prev.suppressed().getAndSet(0);
        suppressions.put(jobId, new Suppression(now, new AtomicInteger(0)));
        return carried;
    }

    // ---------------------- message ----------------------

    String render(XxlJobInfo info, XxlJobLog jobLog, int suppressed) {
        StringBuilder sb = new StringBuilder("🔴 ");
        if (!env.isBlank()) {
            sb.append('[').append(env).append("] ");
        }
        sb.append("XXL-JOB 任务执行失败：").append(info.getName());

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("任务", info.getId() + " / " + info.getName());
        fields.put("执行器", groupName(info.getJobGroup()));
        fields.put("JobHandler", StringTool.isNotBlank(info.getExecutorHandler()) ? info.getExecutorHandler() : "-");
        if (StringTool.isNotBlank(jobLog.getExecutorAddress())) {
            fields.put("执行地址", jobLog.getExecutorAddress());
        }
        if (jobLog.getTriggerTime() != null) {
            fields.put("调度时间", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(jobLog.getTriggerTime()));
        }
        if (jobLog.getTriggerCode() != XxlJobContext.HANDLE_CODE_SUCCESS) {
            fields.put("调度结果", jobLog.getTriggerCode() + " " + abbreviate(stripHtml(jobLog.getTriggerMsg())));
        }
        if (jobLog.getHandleCode() > 0 && jobLog.getHandleCode() != XxlJobContext.HANDLE_CODE_SUCCESS) {
            fields.put("执行结果", jobLog.getHandleCode() + " " + abbreviate(stripHtml(jobLog.getHandleMsg())));
        }
        fields.put("日志ID", String.valueOf(jobLog.getId()));
        if (!adminUrl.isBlank()) {
            fields.put("执行日志", adminUrl + "/joblog/logDetailPage?id=" + jobLog.getId());
        }
        fields.forEach((k, v) -> sb.append('\n').append(k).append(": ").append(v));

        if (suppressed > 0) {
            sb.append("\n（距上次告警期间该任务另有 ").append(suppressed).append(" 次失败被抑制）");
        }
        return sb.toString();
    }

    private static String groupName(int jobGroup) {
        try {
            XxlJobGroup group = XxlJobAdminBootstrap.getInstance().getXxlJobGroupMapper().load(jobGroup);
            return group != null ? group.getName() + " (" + group.getAppname() + ")" : String.valueOf(jobGroup);
        } catch (Exception e) {
            return String.valueOf(jobGroup);
        }
    }

    /** 调度中心里的失败原因带 HTML 标签（&lt;br&gt;、&lt;span&gt;），IM 文本消息里要剥掉 */
    public static String stripHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&nbsp;", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MSG_FIELD_LIMIT ? s : s.substring(0, MSG_FIELD_LIMIT) + "…";
    }

    // ---------------------- send ----------------------

    /**
     * @return true 飞书确认投递（HTTP 2xx 且 body.code == 0）
     */
    boolean send(String text) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!secret.isBlank()) {
            // 飞书签名：以 "{timestamp}\n{secret}" 为 key，对空字节数组做 HMAC-SHA256 再 Base64；timestamp 单位秒，须在 1 小时内
            String ts = String.valueOf(Instant.now().getEpochSecond());
            body.put("timestamp", ts);
            body.put("sign", sign(ts, secret));
        }
        body.put("msg_type", "text");
        body.put("content", Map.of("text", text));

        HttpResponse response = HttpTool.createRequest()
                .url(webhookUrl)
                .method(Method.POST)
                .contentType(ContentType.JSON)
                .connectTimeout(HTTP_TIMEOUT_MILLIS)
                .readTimeout(HTTP_TIMEOUT_MILLIS)
                .body(GsonTool.toJson(body))
                .execute();

        String payload = response.response() == null ? "" : response.response();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logger.warn(">>>>>>>>>>> xxl-job lark alarm: webhook returned HTTP {}: {}", response.statusCode(), abbreviate(payload));
            return false;
        }
        // 关键：HTTP 200 不代表送达，必须看 body 的 code
        int code = parseCode(payload);
        if (code != 0) {
            logger.warn(">>>>>>>>>>> xxl-job lark alarm: webhook rejected, code={} (19021=sign/timestamp, 11232=rate limit), body={}", code, abbreviate(payload));
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static int parseCode(String payload) {
        if (StringTool.isBlank(payload)) {
            return 0;
        }
        try {
            Map<String, Object> json = GsonTool.fromJson(payload, Map.class);
            Object code = json == null ? null : json.get("code");
            if (code instanceof Number n) {
                return n.intValue();
            }
            if (code instanceof String s && !s.isBlank()) {
                return Integer.parseInt(s.trim());
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String sign(String timestamp, String secret) throws Exception {
        String key = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
    }

}
