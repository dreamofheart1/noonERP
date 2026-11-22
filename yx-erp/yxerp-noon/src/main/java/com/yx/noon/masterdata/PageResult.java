package com.yx.noon.masterdata;

import java.util.List;

/**
 * 分页结果（契约 v3）：rows 为当前页数据，total 为「授权 + 筛选条件」下的真实总数（不受 LIMIT/OFFSET 影响）。
 */
public record PageResult<T>(List<T> rows, long total)
{
    public PageResult
    {
        rows = rows == null ? List.of() : List.copyOf(rows);
        if (total < 0) total = 0L;
    }

    public static <T> PageResult<T> empty()
    {
        return new PageResult<>(List.of(), 0L);
    }

    public static <T> PageResult<T> of(List<T> rows, long total)
    {
        return new PageResult<>(rows, total);
    }

    public boolean isEmpty()
    {
        return rows.isEmpty();
    }
}
