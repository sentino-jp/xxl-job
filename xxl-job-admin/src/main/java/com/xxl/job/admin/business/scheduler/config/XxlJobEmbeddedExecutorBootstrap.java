package com.xxl.job.admin.business.scheduler.config;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.tool.core.StringTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Generic HTTP executor embedded in the scheduler process.
 *
 * <p>Replaces the former standalone {@code xxl-job-executor-http} module. It carries no business
 * code: the only job handler is the built-in {@code httpJobHandler} from xxl-job-core, so jobs
 * configured with invoke type "HTTP" are routed to the scheduler nodes themselves and turned into
 * HTTP requests against business services. Every scheduler node is therefore one instance of the
 * {@code http-executor} group; {@code XXL_JOB_EXECUTOR_ENABLED=false} turns it off on a node.
 *
 * <p>The executor is started on {@link ApplicationReadyEvent} (the web server is already
 * listening, so the first registry heartbeat to the loopback address succeeds immediately) and
 * stopped on {@link ContextClosedEvent} (the web server is still up, so registry removal reaches
 * the scheduler). A plain {@link XxlJobExecutor} is used instead of the Spring variant because
 * there are no {@code @XxlJob} methods to scan here.
 *
 * @author sentino 2026-09-16
 */
@Component
public class XxlJobEmbeddedExecutorBootstrap {
    private static final Logger logger = LoggerFactory.getLogger(XxlJobEmbeddedExecutorBootstrap.class);

    @Value("${xxl.job.executor.enabled:true}")
    private boolean enabled;

    /** scheduler address the embedded executor registers to; empty = loopback of this process */
    @Value("${xxl.job.executor.admin-addresses:}")
    private String adminAddresses;

    @Value("${server.port:8088}")
    private int serverPort;

    @Value("${xxl.job.timeout:3}")
    private int timeout;

    @Value("${xxl.job.executor.appname:http-executor}")
    private String appname;

    @Value("${xxl.job.executor.accessToken:}")
    private String accessToken;

    @Value("${xxl.job.executor.ip:}")
    private String ip;

    @Value("${xxl.job.executor.port:9999}")
    private int port;

    @Value("${xxl.job.executor.address:}")
    private String address;

    @Value("${xxl.job.executor.logpath}")
    private String logPath;

    @Value("${xxl.job.executor.logretentiondays:30}")
    private int logRetentionDays;

    @Value("${xxl.job.executor.httpjob.allowdomains:}")
    private String httpJobAllowDomains;

    private volatile XxlJobExecutor executor;

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start(ApplicationReadyEvent event) throws Exception {
        if (!enabled) {
            logger.info(">>>>>>>>>>> xxl-job embedded http executor disabled (xxl.job.executor.enabled=false).");
            return;
        }
        if (executor != null) {
            return;
        }
        // First rollout: the executor group (and its AccessToken) does not exist yet, so the token is
        // intentionally left empty. XxlJobExecutor.start() would throw on a blank token and take the
        // whole scheduler down with it; skip the embedded executor instead so scheduling keeps working.
        // HTTP jobs simply have no executor until the token is filled in and the node is restarted.
        if (StringTool.isBlank(accessToken)) {
            logger.warn(">>>>>>>>>>> xxl-job embedded http executor NOT started: accessToken empty. "
                    + "Create the '{}' executor group in the console, put its AccessToken into XXL_JOB_EXECUTOR_ACCESS_TOKEN and restart this node.",
                    appname);
            return;
        }

        String addresses = StringTool.isNotBlank(adminAddresses)
                ? adminAddresses
                : "http://127.0.0.1:" + resolveServerPort(event.getApplicationContext());

        XxlJobExecutor xxlJobExecutor = new XxlJobExecutor();
        xxlJobExecutor.setAdminAddresses(addresses);
        xxlJobExecutor.setTimeout(timeout);
        xxlJobExecutor.setEnabled(true);
        xxlJobExecutor.setAppname(appname);
        xxlJobExecutor.setAccessToken(accessToken);
        xxlJobExecutor.setIp(ip);
        xxlJobExecutor.setPort(port);
        xxlJobExecutor.setAddress(address);
        xxlJobExecutor.setLogPath(logPath);
        xxlJobExecutor.setLogRetentionDays(logRetentionDays);
        // this executor exists for the built-in http handler, so it is always enabled here
        xxlJobExecutor.setHttpJobEnabled(true);
        xxlJobExecutor.setHttpJobAllowDomains(httpJobAllowDomains);
        xxlJobExecutor.start();

        executor = xxlJobExecutor;
        logger.info(">>>>>>>>>>> xxl-job embedded http executor started, appname:{}, port:{}, adminAddresses:{}, allowDomains:{}",
                appname, port, addresses, StringTool.isBlank(httpJobAllowDomains) ? "<all>" : httpJobAllowDomains);
    }

    @EventListener(ContextClosedEvent.class)
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        try {
            executor.destroy();
            logger.info(">>>>>>>>>>> xxl-job embedded http executor stopped.");
        } catch (Throwable e) {
            logger.error(">>>>>>>>>>> xxl-job embedded http executor stop error", e);
        } finally {
            executor = null;
        }
    }

    /** actual listening port (server.port=0 in tests picks a random one) */
    private int resolveServerPort(ApplicationContext context) {
        if (context instanceof WebServerApplicationContext webContext && webContext.getWebServer() != null) {
            int actual = webContext.getWebServer().getPort();
            if (actual > 0) {
                return actual;
            }
        }
        return serverPort;
    }

}
