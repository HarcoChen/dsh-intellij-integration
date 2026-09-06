package top.harcochen.dsh;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

final class DshDeepSeekPricing {

    enum Period {
        PEAK,
        OFF_PEAK
    }

    private static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);
    private static final Instant WEEKEND_OFF_PEAK_EFFECTIVE =
            ZonedDateTime.of(2026, 8, 23, 0, 0, 0, 0, BEIJING).toInstant();

    static Period current() {
        return at(Instant.now());
    }

    static Period at(Instant instant) {
        ZonedDateTime beijing = instant.atZone(BEIJING);
        boolean weekend = beijing.getDayOfWeek().getValue() >= 6;
        if (weekend && !instant.isBefore(WEEKEND_OFF_PEAK_EFFECTIVE)) return Period.OFF_PEAK;
        int minute = beijing.getHour() * 60 + beijing.getMinute();
        if (minute >= 540 && minute < 720) return Period.PEAK;
        if (minute >= 840 && minute < 1080) return Period.PEAK;
        return Period.OFF_PEAK;
    }

    private DshDeepSeekPricing() {}
}
