package com.localagent.service;

public final class BlockMessages {
    public static final String AMAP_FAILED = "抱歉，暂时无法获取地点信息，请稍后重试";
    public static final String NO_POI_FOUND = "抱歉，未找到符合条件的地点，请尝试调整需求";
    public static final String LLM_FAILED = "抱歉，方案生成失败，请重试";
    public static final String ROUTE_FAILED = "抱歉，路线规划失败，请稍后重试";
    public static final String VALIDATION_FAILED = "抱歉，方案校验失败，请重试";
    public static final String ALL_BLOCKED = "抱歉，当前无法生成方案，请稍后重试";

    private BlockMessages() {
    }
}
