package com.flashdeal.product;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProductCategory {
    KEYBOARD("키보드"),
    MOUSE("마우스"),
    MONITOR("모니터"),
    AUDIO("오디오"),
    ACCESSORY("액세서리");

    private final String label;
}
