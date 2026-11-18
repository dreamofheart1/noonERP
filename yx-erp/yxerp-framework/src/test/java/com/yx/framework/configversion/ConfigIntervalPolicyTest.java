package com.yx.framework.configversion;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.yx.common.config.EffectiveConfigResolver.Version;
import com.yx.framework.configversion.VersionedConfigReader.Row;
import static org.junit.jupiter.api.Assertions.*;

class ConfigIntervalPolicyTest
{
    private final LocalDateTime start = LocalDateTime.parse("2026-11-01T00:00:00");
    private Version version(String scope, LocalDateTime from, LocalDateTime to)
    { return new Version("window", scope, "15", from, to); }

    @Test void adjacentIntervalsAllowedButOverlapRejected()
    {
        Row old = new Row(1, version("AE", start, start.plusDays(10)), null);
        assertDoesNotThrow(() -> ConfigIntervalPolicy.requireNoOverlap(version("AE", start.plusDays(10), null), List.of(old)));
        assertThrows(IllegalStateException.class, () -> ConfigIntervalPolicy.requireNoOverlap(version("AE", start.plusDays(9), null), List.of(old)));
        assertThrows(IllegalStateException.class, () -> ConfigIntervalPolicy.requireNoOverlap(version("AE", start.minusDays(1), start.plusDays(1)), List.of(old)));
    }

    @Test void revocationClosesIntervalWithoutErasingEarlierHistory()
    {
        var revoked = start.plusDays(5);
        Row old = new Row(1, version("AE", start, null), revoked);
        assertDoesNotThrow(() -> ConfigIntervalPolicy.requireNoOverlap(version("AE", revoked, null), List.of(old)));
        assertThrows(IllegalStateException.class, () -> ConfigIntervalPolicy.requireNoOverlap(version("AE", revoked.minusNanos(1), null), List.of(old)));
        Row cancelledBeforeStart = new Row(2, version("AE", start, null), start.minusDays(1));
        assertDoesNotThrow(() -> ConfigIntervalPolicy.requireNoOverlap(version("AE", start, null), List.of(cancelledBeforeStart)));
    }

    @Test void mismatchedCandidateFailsClosedAndOpenIntervalsOverlap()
    {
        Row wrong = new Row(1, version("SA", start, null), null);
        assertThrows(IllegalArgumentException.class, () -> ConfigIntervalPolicy.requireNoOverlap(version("AE", start, null), List.of(wrong)));
        Row old = new Row(2, version("AE", start, null), null);
        assertThrows(IllegalStateException.class, () -> ConfigIntervalPolicy.requireNoOverlap(version("AE", start.plusYears(1), null), List.of(old)));
    }
}
