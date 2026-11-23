package com.yx.noon.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 统一限速器单测：固定间隔、并发串行、一次执行、429 退避有上限。 */
class NoonRequestThrottleTest
{
    @Test
    void serialCallsRespectMinimumInterval()
    {
        NoonRequestThrottle throttle = new NoonRequestThrottle(120L, 2);
        List<Long> starts = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            throttle.run(() -> { starts.add(System.currentTimeMillis()); return new NoonRequestThrottle.Response<>(200, "ok"); });
        for (int i = 1; i < starts.size(); i++)
            assertTrue(starts.get(i) - starts.get(i - 1) >= 100L, "两次请求间隔应不小于约定值（允许少量调度误差）");
    }

    @Test
    void concurrentCallsDoNotBypassInterval() throws Exception
    {
        NoonRequestThrottle throttle = new NoonRequestThrottle(120L, 2);
        int n = 3;
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try
        {
            for (int i = 0; i < n; i++)
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    throttle.run(() -> { starts.add(System.currentTimeMillis()); return new NoonRequestThrottle.Response<>(200, "ok"); });
                    return null;
                });
            ready.await();
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        finally
        {
            pool.shutdownNow();
        }
        assertEquals(n, starts.size());
        starts.sort(Long::compareTo);
        for (int i = 1; i < starts.size(); i++)
            assertTrue(starts.get(i) - starts.get(i - 1) >= 100L, "并发不得绕过间隔");
    }

    @Test
    void oneAttemptExecutesRequestExactlyOnce()
    {
        NoonRequestThrottle throttle = new NoonRequestThrottle(1L, 2);
        int[] calls = {0};
        NoonRequestThrottle.Response<String> r = throttle.run(() ->
        {
            calls[0]++;
            return new NoonRequestThrottle.Response<>(200, "ok");
        });
        assertEquals("ok", r.body());
        assertEquals(1, calls[0], "一次尝试只应执行一次请求");
    }

    @Test
    void rateLimitedRequestRetriesWithCeiling()
    {
        NoonRequestThrottle throttle = new NoonRequestThrottle(1L, 2);
        int[] calls = {0};
        assertThrows(IllegalStateException.class, () -> throttle.run(() ->
        {
            calls[0]++;
            return new NoonRequestThrottle.Response<>(429, "limited");
        }));
        assertEquals(3, calls[0], "首次 + 2 次退避重试，之后必须停止");
    }

    @Test
    void nonRateLimitErrorDoesNotRetry()
    {
        NoonRequestThrottle throttle = new NoonRequestThrottle(1L, 3);
        int[] calls = {0};
        NoonRequestThrottle.Response<String> r = throttle.run(() ->
        {
            calls[0]++;
            return new NoonRequestThrottle.Response<>(401, "unauthorized");
        });
        assertEquals(401, r.status());
        assertEquals(1, calls[0], "非限流错误不应重试");
    }
}
