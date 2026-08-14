package com.badwolfmc.rolerewards.schedule;

import com.badwolfmc.rolerewards.config.RewardDefinition;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class ScheduleCalculator {
    private ScheduleCalculator() {
    }

    public static String period(ZonedDateTime now) {
        return YearMonth.from(now).toString();
    }

    public static ZonedDateTime dueAt(RewardDefinition reward, YearMonth period, ZoneId zoneId) {
        int day = Math.min(reward.dayOfMonth(), period.lengthOfMonth());
        return period.atDay(day).atTime(reward.time()).atZone(zoneId);
    }

    public static ZonedDateTime nextDue(RewardDefinition reward, ZonedDateTime now, boolean currentPeriodRecorded) {
        YearMonth current = YearMonth.from(now);
        ZonedDateTime currentDue = dueAt(reward, current, now.getZone());
        if (!currentPeriodRecorded && !now.isBefore(currentDue)) {
            return currentDue;
        }
        if (!currentPeriodRecorded && now.isBefore(currentDue)) {
            return currentDue;
        }
        return dueAt(reward, current.plusMonths(1), now.getZone());
    }
}
