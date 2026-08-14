package com.badwolfmc.rolerewards.schedule;

import com.badwolfmc.rolerewards.config.RewardDefinition;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduleCalculatorTest {
    private final RewardDefinition reward = new RewardDefinition(
            "companion", "companion", true, true, 31, LocalTime.of(6, 0), List.of("points give {player} 50")
    );

    @Test
    void clampsDayToLastDayOfShortMonth() {
        var due = ScheduleCalculator.dueAt(reward, YearMonth.of(2027, 2), ZoneId.of("America/New_York"));
        assertEquals(28, due.getDayOfMonth());
        assertEquals(LocalTime.of(6, 0), due.toLocalTime());
    }

    @Test
    void nextDueMovesToNextMonthAfterCurrentPeriodRecorded() {
        ZonedDateTime now = ZonedDateTime.of(2026, 8, 14, 3, 0, 0, 0, ZoneId.of("America/New_York"));
        var next = ScheduleCalculator.nextDue(reward, now, true);
        assertEquals(YearMonth.of(2026, 9), YearMonth.from(next));
    }
}
