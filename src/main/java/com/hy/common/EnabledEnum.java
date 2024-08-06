package com.hy.common;

import lombok.Getter;

@Getter
public enum EnabledEnum {
    ENABLED(1), //启用
    DISABLE(0); //禁用

    // 获取整数值
    private final int value;

    // 构造函数
    EnabledEnum(int value) {
        this.value = value;
    }

    // 根据整数值获取枚举实例
    public static EnabledEnum fromValue(int value) {
        for (EnabledEnum enabledEnum : EnabledEnum.values()) {
            if (enabledEnum.value == value) {
                return enabledEnum;
            }
        }
        throw new IllegalArgumentException("Unexpected value: " + value);
    }
}
