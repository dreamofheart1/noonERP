package com.yx.framework.configversion;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.yx.common.config.EffectiveConfigResolver.Version;
import com.yx.framework.configversion.VersionedConfigReader.Row;
import com.yx.framework.security.site.SiteAuthGuard;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;


class VersionedConfigReaderTest
{
    private final LocalDateTime t = LocalDateTime.parse("2026-11-10T00:00:00");
    private final SiteAuthGuard guard = new SiteAuthGuard(null) {
        @Override public String requireSite(String site) {
            if ("SA".equals(site)) throw new AccessDeniedException("denied");
            return site;
        }
    };
    private final VersionedConfigReader reader = new VersionedConfigReader(guard);
    private Row row(long id, String scope, String value, LocalDateTime revoked)
    { return new Row(id, new Version("window", scope, value, t.minusDays(5), null), revoked); }

    @Test void historicalResolutionAndSnapshotRemainStableAfterRevocation()
    {
        var rows = new ArrayList<>(List.of(row(1, "GLOBAL", "14", null), row(2, "AE", "15", t)));
        VersionedConfigReader.Repository repo = (key, site) -> rows;
        var snapshot = reader.resolve(repo, "window", "AE", t.minusNanos(1));
        assertEquals(2, snapshot.versionId());
        assertEquals("15", snapshot.value());
        assertEquals("14", reader.resolve(repo, "window", "AE", t).value());
        rows.clear();
        assertEquals("15", reader.valueFromSnapshot(snapshot));
        assertThrows(IllegalStateException.class, () -> reader.resolve(repo, "window", "AE", t));
    }

    @Test void authorizationPrecedesRepositoryAndAppliesToSnapshots()
    {
        var calls = new int[1];
        VersionedConfigReader.Repository repo = (key, site) -> { calls[0]++; return List.of(); };
        assertThrows(AccessDeniedException.class, () -> reader.resolve(repo, "window", "SA", t));
        assertEquals(0, calls[0]);
        var snapshot = new VersionedConfigReader.Snapshot(1, "window", "SA", "GLOBAL", "14", t);
        assertThrows(AccessDeniedException.class, () -> reader.valueFromSnapshot(snapshot));
    }

    @Test void overlappingOrForeignRowsAreRejected()
    {
        assertThrows(IllegalStateException.class, () -> reader.resolve((k,s) -> List.of(row(1,"AE","a",null),row(2,"AE","b",null)), "window", "AE", t));
        assertThrows(IllegalStateException.class, () -> reader.resolve((k,s) -> List.of(row(1,"SA","a",null)), "window", "AE", t));
        assertThrows(IllegalStateException.class, () -> reader.resolve((k,s) -> List.of(row(1,"AE","a",null),row(1,"GLOBAL","b",null)), "window", "AE", t));
    }
}
