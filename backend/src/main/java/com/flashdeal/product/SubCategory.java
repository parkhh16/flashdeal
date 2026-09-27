package com.flashdeal.product;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/** 소분류. 대분류(ProductCategory)에 종속되며 선언 순서가 사이드바 표시 순서다 */
@Getter
@RequiredArgsConstructor
public enum SubCategory {
    KEYBOARD_MECHANICAL(ProductCategory.KEYBOARD, "기계식"),
    KEYBOARD_WIRELESS(ProductCategory.KEYBOARD, "무선"),
    KEYBOARD_GAMING(ProductCategory.KEYBOARD, "게이밍"),
    MOUSE_WIRELESS(ProductCategory.MOUSE, "무선"),
    MOUSE_GAMING(ProductCategory.MOUSE, "게이밍"),
    MONITOR_OFFICE(ProductCategory.MONITOR, "사무용"),
    MONITOR_GAMING(ProductCategory.MONITOR, "게이밍"),
    AUDIO_EARPHONE(ProductCategory.AUDIO, "이어폰"),
    AUDIO_HEADSET(ProductCategory.AUDIO, "헤드셋"),
    AUDIO_SPEAKER(ProductCategory.AUDIO, "스피커"),
    ACCESSORY_PAD(ProductCategory.ACCESSORY, "장패드"),
    ACCESSORY_STAND(ProductCategory.ACCESSORY, "거치대"),
    ACCESSORY_ETC(ProductCategory.ACCESSORY, "기타");

    private final ProductCategory parent;
    private final String label;

    public static SubCategory defaultOf(ProductCategory category) {
        return Arrays.stream(values()).filter(s -> s.parent == category).findFirst().orElseThrow();
    }
}
