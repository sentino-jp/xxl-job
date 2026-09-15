package com.xxl.job.core.openapi.executor.impl;

import com.xxl.job.core.constant.ExecutorBlockStrategyEnum;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.log.XxlJobFileAppender;
import com.xxl.job.core.openapi.executor.ExecutorBiz;
import com.xxl.job.core.openapi.executor.dto.*;
import com.xxl.job.core.thread.JobThread;
import com.xxl.tool.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

/**
 * Created by xuxueli on 17/3/1.
 */
public class ExecutorBizImpl implements ExecutorBiz {
    private static final Logger logger = LoggerFactory.getLogger(ExecutorBizImpl.class);

    @Override
    public Response<String> beat() {
        return Response.ofSuccess();
    }

    @Override
    public Response<String> idleBeat(IdleBeatRequest idleBeatRequest) {

        // isRunningOrHasQueue
        boolean isRunningOrHasQueue = false;
        JobThread jobThread = XxlJobExecutor.getInstance().loadJobThread(idleBeatRequest.getJobId());
        if (jobThread != null && jobThread.isRunningOrHasQueue()) {
            isRunningOrHasQueue = true;
        }

        if (isRunningOrHasQueue) {
            return Response.ofFail("job thread is running or has trigger queue.");
        }
        return Response.ofSuccess();
    }

    @Override
    public Response<String> trigger(TriggerRequest triggerRequest) {

        // load job info：jobHandler + jobThread
        JobThread jobThread = XxlJobExecutor.getInstance().loadJobThread(triggerRequest.getJobId());
        IJobHandler jobHandler = jobThread!=null?jobThread.getHandler():null;
        String removeOldReason = null;

        // new jobhandler (BEAN)
        IJobHandler newJobHandler = XxlJobExecutor.getInstance().loadJobHandler(triggerRequest.getExecutorHandler());

        // valid old jobThread
        if (jobThread!=null && jobHandler != newJobHandler) {
            // change handler, need kill old thread
            removeOldReason = "change jobhandler, and terminate the old job thread.";

            jobThread = null;
            jobHandler = null;
        }

        // valid handler
        if (jobHandler == null) {
            jobHandler = newJobHandler;
            if (jobHandler == null) {
                return Response.of(XxlJobContext.HANDLE_CODE_FAIL, "job handler [" + triggerRequest.getExecutorHandler() + "] not found.");
            }
        }

        // executor block strategy
        if (jobThread != null) {
            ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(triggerRequest.getExecutorBlockStrategy(), null);
            if (ExecutorBlockStrategyEnum.DISCARD_LATER == blockStrategy) {
                // discard when running
                if (jobThread.isRunningOrHasQueue()) {
                    return Response.of(XxlJobContext.HANDLE_CODE_FAIL, "block strategy effect："+ExecutorBlockStrategyEnum.DISCARD_LATER.getTitle());
                }
            } else if (ExecutorBlockStrategyEnum.COVER_EARLY == blockStrategy) {
                // kill running jobThread
                if (jobThread.isRunningOrHasQueue()) {
                    removeOldReason = "block strategy effect：" + ExecutorBlockStrategyEnum.COVER_EARLY.getTitle();

                    jobThread = null;
                }
            } else {
                // just queue trigger
            }
        }

        // replace thread (new or exists invalid)
        if (jobThread == null) {
            jobThread = XxlJobExecutor.getInstance().registJobThread(triggerRequest.getJobId(), jobHandler, removeOldReason);
        }

        // push data to queue
        return jobThread.pushTriggerQueue(triggerRequest);
    }

    @Override
    public Response<String> kill(KillRequest killRequest) {
        // kill handlerThread, and create new one
        JobThread jobThread = XxlJobExecutor.getInstance().loadJobThread(killRequest.getJobId());
        if (jobThread != null) {
            XxlJobExecutor.getInstance().removeJobThread(killRequest.getJobId(), "scheduling center kill job.");
            return Response.ofSuccess();
        }

        return Response.ofSuccess( "job thread already killed.");
    }

    @Override
    public Response<LogData> log(LogRequest logRequest) {
        // log filename: logPath/yyyy-MM-dd/9999.log
        String logFileName = XxlJobFileAppender.makeLogFileName(new Date(logRequest.getLogDateTime()), logRequest.getLogId());

        LogData logResult = XxlJobFileAppender.readLog(logFileName, logRequest.getFromLineNum());
        return Response.ofSuccess(logResult);
    }

}
