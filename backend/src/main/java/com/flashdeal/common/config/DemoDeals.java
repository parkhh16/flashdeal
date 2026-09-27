package com.flashdeal.common.config;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 시드 특가의 시간표. "지금" 기준 상대 시간이라, 언제 봐도 진행 중 / 오픈 예정 / 품절 / 마감 임박 상태가 섞여 보인다.
 * 시드(DataInitializer)와 시연용 재편성 작업(DemoDealRotator)이 같은 표를 쓴다.
 */
public final class DemoDeals {

    private DemoDeals() {
    }

    /**
     * @param startOffset 지금으로부터 오픈까지 (음수면 이미 진행 중)
     * @param endOffset   지금으로부터 종료까지
     * @param stock       회차 시작 시 가용 재고 (0이면 품절 예시)
     */
    public record Slot(Duration startOffset, Duration endOffset, int stock) {

        public LocalDateTime start(LocalDateTime now) {
            return now.plus(startOffset);
        }

        public LocalDateTime end(LocalDateTime now) {
            return now.plus(endOffset);
        }
    }

    public static final Map<String, Slot> SLOTS = Map.of(
            "[한정 100개] 무접점 기계식 키보드 한정판", new Slot(Duration.ofHours(-1), Duration.ofHours(6), 100),
            "슬림 블루투스 멀티페어링 키보드", new Slot(Duration.ofHours(2), Duration.ofHours(26), 50),
            "저소음 무선 마우스", new Slot(Duration.ofMinutes(-30), Duration.ofHours(2), 180),
            "32인치 4K 모니터", new Slot(Duration.ofHours(-3), Duration.ofHours(5), 0),
            "7.1채널 게이밍 헤드셋", new Slot(Duration.ofHours(-2), Duration.ofMinutes(50), 23),
            "USB-C 7in1 멀티 허브", new Slot(Duration.ofHours(5), Duration.ofHours(29), 120));

    public static Slot slot(String name) {
        Slot slot = SLOTS.get(name);
        if (slot == null) throw new IllegalArgumentException("시간표에 없는 시드 특가: " + name);
        return slot;
    }
}
