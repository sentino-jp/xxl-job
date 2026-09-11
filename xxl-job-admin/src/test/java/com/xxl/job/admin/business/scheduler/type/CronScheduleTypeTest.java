package com.xxl.job.admin.business.scheduler.type;

import com.xxl.job.admin.business.model.XxlJobInfo;
import com.xxl.job.admin.business.scheduler.type.strategy.CronScheduleType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CronScheduleTypeTest {

    private static final String CRON_09_00 = "0 0 9 * * ?";
    private static final Date FROM = Date.from(Instant.parse("2026-09-10T00:00:00Z"));

    private static ZonedDateTime next(String timezone) throws Exception {
        XxlJobInfo jobInfo = new XxlJobInfo();
        jobInfo.setScheduleType(ScheduleTypeEnum.CRON.name());
        jobInfo.setScheduleConf(CRON_09_00);
        jobInfo.setScheduleTimezone(timezone);

        Date nextTime = new CronScheduleType().generateNextTriggerTime(jobInfo, FROM);
        return nextTime.toInstant().atZone(ZoneId.of(timezone != null ? timezone : ZoneId.systemDefault().getId()));
    }

    @Test
    void cron_fires_at_local_time_of_job_timezone() throws Exception {
        ZonedDateTime tokyo = next("Asia/Tokyo");
        assertEquals(9, tokyo.getHour());
        assertEquals(0, tokyo.getMinute());
        assertEquals("Asia/Tokyo", tokyo.getZone().getId());

        ZonedDateTime la = next("America/Los_Angeles");
        assertEquals(9, la.getHour());
        assertEquals(0, la.getMinute());

        // 09:00 Tokyo and 09:00 Los Angeles differ by 16 hours on the clock (PDT in September), ignoring which day each lands on
        assertEquals(16 * 3600, Math.floorMod(la.toEpochSecond() - tokyo.toEpochSecond(), 24 * 3600));
    }

    @Test
    void blank_timezone_falls_back_to_admin_default() throws Exception {
        ZonedDateTime byDefault = next(null);
        assertEquals(9, byDefault.getHour());
        assertEquals(0, byDefault.getMinute());
    }

}
