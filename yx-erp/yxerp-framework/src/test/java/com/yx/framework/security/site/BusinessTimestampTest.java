package com.yx.framework.security.site;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import com.yx.common.time.BusinessTimestamp;
import static org.junit.jupiter.api.Assertions.*;

class BusinessTimestampTest
{
    @Test
    void uaeDeadlineCrossesChinaDateWithoutChangingInstantOrSource()
    {
        String raw = "2026-11-18T23:59:59.123+04:00";
        var time = BusinessTimestamp.fromOffset(raw);
        assertEquals(raw, time.sourceValue());
        assertEquals("+04:00", time.sourceZone());
        assertEquals(Instant.parse("2026-11-18T19:59:59.123Z"), time.instant());
        assertEquals(LocalDateTime.parse("2026-11-19T03:59:59.123"), time.chinaTime());
    }

    @Test
    void saudiLocalTimeRequiresExplicitPlatformZone()
    {
        var time = BusinessTimestamp.fromLocal("2026-11-18T23:59:59", ZoneId.of("Asia/Riyadh"));
        assertEquals("Asia/Riyadh", time.sourceZone());
        assertEquals(Instant.parse("2026-11-18T20:59:59Z"), time.instant());
        assertEquals(LocalDateTime.parse("2026-11-19T04:59:59"), time.chinaTime());
    }

    @Test
    void missingOffsetOrZoneIsNeverAssumedToBeChina()
    {
        assertThrows(DateTimeParseException.class, () -> BusinessTimestamp.fromOffset("2026-11-18T23:59:59"));
        assertThrows(NullPointerException.class, () -> BusinessTimestamp.fromLocal("2026-11-18T23:59:59", null));
        assertThrows(DateTimeParseException.class, () -> BusinessTimestamp.fromLocal("2026-02-30T00:00:00", ZoneId.of("Asia/Dubai")));
    }

    @Test
    void daylightSavingGapAndOverlapAreNotSilentlyAdjusted()
    {
        ZoneId newYork = ZoneId.of("America/New_York");
        assertThrows(IllegalArgumentException.class, () -> BusinessTimestamp.fromLocal("2026-03-08T02:30:00", newYork));
        assertThrows(IllegalArgumentException.class, () -> BusinessTimestamp.fromLocal("2026-11-01T01:30:00", newYork));
        var earlier = BusinessTimestamp.fromOffset("2026-11-01T01:30:00-04:00");
        var later = BusinessTimestamp.fromOffset("2026-11-01T01:30:00-05:00");
        assertEquals(3600, later.instant().getEpochSecond() - earlier.instant().getEpochSecond());
    }

    @Test
    void conversionPreservesSubMillisecondPrecision()
    {
        var time = BusinessTimestamp.fromOffset("2026-11-18T00:00:00.123456789Z");
        assertEquals(123456789, time.instant().getNano());
        assertEquals(123456789, time.chinaTime().getNano());
    }

    @Test
    void chinaTimeIsIndependentOfHostDefaultTimeZone()
    {
        TimeZone original = TimeZone.getDefault();
        try
        {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            var time = BusinessTimestamp.fromOffset("2026-11-18T00:00:00Z");
            assertEquals(LocalDateTime.parse("2026-11-18T08:00:00"), time.chinaTime());
        }
        finally { TimeZone.setDefault(original); }
    }
}
