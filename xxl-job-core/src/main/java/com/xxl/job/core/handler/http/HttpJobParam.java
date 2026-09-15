package com.xxl.job.core.handler.http;

import java.util.Map;

/**
 * http job param, serialized as JSON into the job's executorParam.
 *
 * <pre>
 * {
 *   "url": "https://api.example.com/jobs/reconcile",
 *   "method": "POST",
 *   "contentType": "application/json",
 *   "headers": {"X-Admin-Token": "${ADMIN_TOKEN}"},
 *   "cookies": {},
 *   "timeout": 30,
 *   "data": "{}",
 *   "form": {},
 *   "auth": ""
 * }
 * </pre>
 *
 * Values of url / headers / cookies / auth may reference executor-side environment variables
 * or system properties as <code>${NAME}</code>; they are resolved on the executor at run time,
 * so credentials never need to be stored in the scheduler database.
 */
public class HttpJobParam {

    private String url;                         // request url, http or https
    private String method;                      // GET / POST / PUT / DELETE / HEAD, default POST
    private String contentType;                 // content type, default application/json
    private Map<String, String> headers;        // request headers
    private Map<String, String> cookies;        // cookies
    private int timeout;                        // timeout in seconds, default 30
    private String data;                        // request body
    private Map<String, String> form;           // form fields, used with application/x-www-form-urlencoded
    private String auth;                        // authorization header value, such as "Bearer xxx"

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> getCookies() {
        return cookies;
    }

    public void setCookies(Map<String, String> cookies) {
        this.cookies = cookies;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public Map<String, String> getForm() {
        return form;
    }

    public void setForm(Map<String, String> form) {
        this.form = form;
    }

    public String getAuth() {
        return auth;
    }

    public void setAuth(String auth) {
        this.auth = auth;
    }

}
