package com.xxl.job.admin.business.scheduler.type.strategy;

import com.xxl.job.admin.business.model.XxlJobInfo;
import com.xxl.job.admin.business.scheduler.cron.CronExpression;
import com.xxl.job.admin.business.scheduler.type.ScheduleType;
import com.xxl.tool.core.StringTool;

import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;

public class CronScheduleType extends ScheduleType {

    @Override
    public Date generateNextTriggerTime(XxlJobInfo jobInfo, Date fromTime) throws Exception {
        // generate next trigger time, with cron
        CronExpression cronExpression = new CronExpression(jobInfo.getScheduleConf());

        // job-level timezone, fallback to admin default timezone when blank
        if (StringTool.isNotBlank(jobInfo.getScheduleTimezone())) {
            cronExpression.setTimeZone(TimeZone.getTimeZone(ZoneId.of(jobInfo.getScheduleTimezone())));
        }
        return cronExpression.getNextValidTimeAfter(fromTime);
    }

}
