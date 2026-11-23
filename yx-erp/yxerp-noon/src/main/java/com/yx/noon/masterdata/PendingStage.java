package com.yx.noon.masterdata;

import java.util.List;

/** 无可靠数据源、明确标为待接入的阶段；不允许以 0 冒充。 */
public record PendingStage(String stage, String label, String reason)
{
}
