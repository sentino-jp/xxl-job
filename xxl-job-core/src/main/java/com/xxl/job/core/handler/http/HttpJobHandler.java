package com.xxl.job.core.handler.http;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.http.HttpTool;
import com.xxl.tool.http.http.HttpRequest;
import com.xxl.tool.http.http.HttpResponse;
import com.xxl.tool.http.http.enums.ContentType;
import com.xxl.tool.http.http.enums.Method;
import com.xxl.tool.json.GsonTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * built-in http job handler.
 *
 * <p>Registered automatically by every executor under the name {@link #HANDLER_NAME}
 * (unless disabled or a business handler with the same name already exists), so the scheduler
 * console can configure "call this URL on schedule" jobs without any Java code on the business side.
 *
 * <p>Job param is a JSON document described by {@link HttpJobParam}. The executor decides which
 * target hosts are allowed via {@code allowDomains}; the scheduler center never issues outbound
 * requests itself.
 *
 * @author sentino 2026-09-15
 */
public class HttpJobHandler extends IJobHandler {
    private static final Logger logger = LoggerFactory.getLogger(HttpJobHandler.class);

    /** handler name used in the scheduler console */
    public static final String HANDLER_NAME = "httpJobHandler";

    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int MAX_TIMEOUT_SECONDS = 3600;
    private static final int RESPONSE_LOG_LIMIT = 2000;
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "HEAD");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.\\-]+)}");

    /**
     * allowed target domains. Each entry is either a host ("api.example.com"), a host with port
     * ("10.0.1.34:9082"), a wildcard sub-domain (".example.com" matches any sub-domain) or a URL
     * prefix ("https://api.example.com/api/"). Empty means every host is allowed.
     */
    private final List<String> allowDomains;

    public HttpJobHandler() {
        this(Collections.emptyList());
    }

    public HttpJobHandler(List<String> allowDomains) {
        this.allowDomains = allowDomains == null ? Collections.emptyList() : allowDomains.stream()
                .filter(StringTool::isNotBlank).map(String::trim).toList();
        if (this.allowDomains.isEmpty()) {
            logger.warn(">>>>>>>>>>> xxl-job {} has no allow-domain configured, every target host is allowed. " +
                    "Set executor property httpJobAllowDomains to restrict it.", HANDLER_NAME);
        }
    }

    public List<String> getAllowDomains() {
        return allowDomains;
    }

    // ---------------------- execute ----------------------

    @Override
    public void execute() throws Exception {
        String param = XxlJobHelper.getJobParam();

        // parse + validate
        HttpJobParam httpJobParam;
        try {
            httpJobParam = parseParam(param);
        } catch (IllegalArgumentException e) {
            XxlJobHelper.log("http job param invalid: " + e.getMessage());
            XxlJobHelper.handleFail("http job param invalid: " + e.getMessage());
            return;
        }

        // resolve ${ENV} placeholders on the executor side; an unresolved one fails the job
        String url;
        Map<String, String> headers;
        Map<String, String> cookies;
        String auth;
        try {
            url = resolvePlaceholders(httpJobParam.getUrl());
            headers = resolvePlaceholders(httpJobParam.getHeaders());
            cookies = resolvePlaceholders(httpJobParam.getCookies());
            auth = resolvePlaceholders(httpJobParam.getAuth());
        } catch (IllegalStateException e) {
            XxlJobHelper.log(e.getMessage());
            XxlJobHelper.handleFail(e.getMessage());
            return;
        }

        // allow list
        if (!isAllowed(url)) {
            XxlJobHelper.log("url[" + url + "] not allowed by executor allow-domain config " + allowDomains);
            XxlJobHelper.handleFail("url not allowed: " + url);
            return;
        }

        Method method = Method.valueOf(httpJobParam.getMethod().toUpperCase());
        ContentType contentType = matchContentType(httpJobParam.getContentType());
        int timeoutMillis = httpJobParam.getTimeout() * 1000;

        XxlJobHelper.log("----------- http job: {} {} -----------", method, url);

        // request
        HttpResponse httpResponse;
        try {
            HttpRequest request = HttpTool.createRequest()
                    .url(url)
                    .method(method)
                    .contentType(contentType)
                    .connectTimeout(timeoutMillis)
                    .readTimeout(timeoutMillis);
            if (headers != null && !headers.isEmpty()) {
                request.header(headers);
            }
            if (cookies != null && !cookies.isEmpty()) {
                request.cookie(cookies);
            }
            if (StringTool.isNotBlank(httpJobParam.getData())) {
                request.body(httpJobParam.getData());
            }
            if (httpJobParam.getForm() != null && !httpJobParam.getForm().isEmpty()) {
                request.form(httpJobParam.getForm());
            }
            if (StringTool.isNotBlank(auth)) {
                request.auth(auth);
            }
            httpResponse = request.execute();
        } catch (Exception e) {
            XxlJobHelper.log(e);
            XxlJobHelper.handleFail("http request error: " + e.getMessage());
            return;
        }

        // result
        int statusCode = httpResponse.statusCode();
        String body = httpResponse.response();
        XxlJobHelper.log("StatusCode: {}", statusCode);
        XxlJobHelper.log("Response: <br>{}", abbreviate(body));

        if (statusCode >= 200 && statusCode < 300) {
            XxlJobHelper.handleSuccess("http " + statusCode);
        } else {
            XxlJobHelper.handleFail("http status " + statusCode);
        }
    }

    // ---------------------- param ----------------------

    /**
     * parse and normalize job param; throws IllegalArgumentException with a human readable reason.
     * Shared with the scheduler center for form validation.
     */
    public static HttpJobParam parseParam(String param) {
        if (StringTool.isBlank(param)) {
            throw new IllegalArgumentException("param is empty");
        }
        HttpJobParam httpJobParam;
        try {
            httpJobParam = GsonTool.fromJson(param, HttpJobParam.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("param is not valid JSON");
        }
        if (httpJobParam == null) {
            throw new IllegalArgumentException("param is empty");
        }

        // url
        if (StringTool.isBlank(httpJobParam.getUrl())) {
            throw new IllegalArgumentException("url is required");
        }
        String url = httpJobParam.getUrl().trim();
        httpJobParam.setUrl(url);
        if (!PLACEHOLDER.matcher(url).find()) {
            URI uri;
            try {
                uri = new URI(url);
            } catch (Exception e) {
                throw new IllegalArgumentException("url is malformed");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!(scheme.equals("http") || scheme.equals("https")) || StringTool.isBlank(uri.getHost())) {
                throw new IllegalArgumentException("url must be http(s) with a host");
            }
        }

        // method
        String method = StringTool.isBlank(httpJobParam.getMethod()) ? "POST" : httpJobParam.getMethod().trim().toUpperCase();
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalArgumentException("method must be one of " + ALLOWED_METHODS);
        }
        httpJobParam.setMethod(method);

        // timeout
        if (httpJobParam.getTimeout() <= 0) {
            httpJobParam.setTimeout(DEFAULT_TIMEOUT_SECONDS);
        }
        if (httpJobParam.getTimeout() > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("timeout must be <= " + MAX_TIMEOUT_SECONDS + " seconds");
        }

        // content type
        if (StringTool.isBlank(httpJobParam.getContentType())) {
            httpJobParam.setContentType(ContentType.JSON.getValue());
        }
        return httpJobParam;
    }

    /**
     * validate job param, return null if valid, otherwise the reason.
     */
    public static String validParam(String param) {
        try {
            parseParam(param);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // ---------------------- allow list ----------------------

    public boolean isAllowed(String url) {
        if (allowDomains.isEmpty()) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return false;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        String hostPort = uri.getPort() > 0 ? host + ":" + uri.getPort() : host;

        for (String entry : allowDomains) {
            String rule = entry.toLowerCase();
            if (rule.contains("://")) {
                if (url.toLowerCase().startsWith(rule)) {
                    return true;
                }
            } else if (rule.startsWith(".")) {
                if (host.endsWith(rule) || host.equals(rule.substring(1))) {
                    return true;
                }
            } else if (host.equals(rule) || hostPort.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------- helpers ----------------------

    static ContentType matchContentType(String value) {
        if (StringTool.isBlank(value)) {
            return ContentType.JSON;
        }
        String v = value.trim().toLowerCase();
        for (ContentType item : ContentType.values()) {
            String itemValue = item.getValue().toLowerCase();
            if (v.equals(itemValue) || v.equals(item.name().toLowerCase()) || itemValue.startsWith(v) || v.startsWith(itemValue.split(";")[0])) {
                return item;
            }
        }
        return ContentType.JSON;
    }

    /**
     * replace ${NAME} with executor environment variable or system property; unknown names are kept as is.
     */
    static String resolvePlaceholders(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String resolved = System.getenv(name);
            if (resolved == null) {
                resolved = System.getProperty(name);
            }
            if (resolved == null) {
                // a missing credential must never be sent to the target as a literal string
                throw new IllegalStateException("placeholder ${" + name + "} not found in executor env or system properties");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static Map<String, String> resolvePlaceholders(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return values;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            result.put(e.getKey(), resolvePlaceholders(e.getValue()));
        }
        return result;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > RESPONSE_LOG_LIMIT ? body.substring(0, RESPONSE_LOG_LIMIT) + " ...(" + body.length() + " chars)" : body;
    }

}
