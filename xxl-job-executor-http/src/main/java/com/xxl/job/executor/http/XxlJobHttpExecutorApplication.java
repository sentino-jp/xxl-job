package com.xxl.job.executor.http;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Generic HTTP executor.
 *
 * <p>Carries no business code: the only job handler is the built-in {@code httpJobHandler}
 * registered by xxl-job-core. Jobs configured in the scheduler console with invoke type "HTTP"
 * are routed here and turned into HTTP requests against business services.
 *
 * @author sentino 2026-09-15
 */
@SpringBootApplication
public class XxlJobHttpExecutorApplication {

    public static void main(String[] args) {
        SpringApplication.run(XxlJobHttpExecutorApplication.class, args);
    }

}
