package com.yx.framework.config;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.yx.common.config.EffectiveConfigResolver;
import com.yx.common.config.EffectiveConfigResolver.Version;
import static org.junit.jupiter.api.Assertions.*;

class EffectiveConfigResolverTest
{
    private final LocalDateTime t = LocalDateTime.parse("2026-11-10T00:00:00");
    private Version v(String scope, String value, LocalDateTime from, LocalDateTime to)
    { return new Version("return_window", scope, value, from, to); }

    @Test void siteWinsEvenWhenGlobalIsNewerAndFallsBackAtExpiry()
    {
        var rows = List.of(v("AE", "15", t.minusDays(9), t.plusDays(2)), v("GLOBAL", "14", t, null));
        assertEquals("15", EffectiveConfigResolver.resolve(rows, "return_window", "AE", t.plusDays(1)).value());
        assertEquals("14", EffectiveConfigResolver.resolve(rows, "return_window", "AE", t.plusDays(2)).value());
        assertEquals("14", EffectiveConfigResolver.resolve(rows, "return_window", "SA", t).value());
        assertThrows(IllegalStateException.class, () -> EffectiveConfigResolver.resolve(rows, "return_window", "SA", t.minusNanos(1)));
    }
    @Test void adjacentIntervalsDoNotOverlapAndReturnOriginalVersion()
    {
        var before = v("GLOBAL", "old", t.minusDays(1), t);
        var after = v("GLOBAL", "new", t, null);
        assertSame(before, EffectiveConfigResolver.resolve(List.of(before, after), "return_window", "AE", t.minusNanos(1)));
        assertSame(after, EffectiveConfigResolver.resolve(List.of(before, after), "return_window", "AE", t));
    }
    @Test void overlappingVersionsAndMissingValuesFailClosed()
    {
        var duplicate = List.of(v("GLOBAL", "a", t, null), v("GLOBAL", "b", t, null));
        assertThrows(IllegalStateException.class, () -> EffectiveConfigResolver.resolve(duplicate, "return_window", "AE", t));
        assertThrows(IllegalStateException.class, () -> EffectiveConfigResolver.resolve(List.of(), "return_window", "AE", t));
        assertThrows(IllegalArgumentException.class, () -> EffectiveConfigResolver.resolve(List.of(), "return_window", "GLOBAL", t));
        assertThrows(IllegalArgumentException.class, () -> v("AE", "x", t, t));
    }
}
