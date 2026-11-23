package com.yx.noon.sync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 进程内共享一个限速实例：所有 Noon 主动请求（既有 SKU 同步、Seller Lab 查询、
 * 分页与后台任务）必须注入同一个 bean，避免并发路径各自 sleep 绕过 3 秒间隔。
 *
 * <p>注意：仅单进程内生效；多实例部署需外部共享限流。</p>
 */
@Configuration
public class NoonThrottleConfig
{
    @Bean
    public NoonRequestThrottle noonRequestThrottle()
    {
        return NoonRequestThrottle.defaults(); // 3s 间隔，429/503 最多 3 次有界退避
    }
}
