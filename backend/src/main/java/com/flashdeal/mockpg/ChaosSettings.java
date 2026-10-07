package com.flashdeal.mockpg;

import org.springframework.stereotype.Component;

/**
 * Mock PG 장애 주입 설정. 결제 장애 시나리오 테스트(PaymentFailureScenarioTest)가 코드에서 바꾼다.
 * 운영 API로는 노출하지 않는다.
 *
 * @param latencyMs              PG 응답 지연
 * @param failureRate            PG가 처리하지 않고 500을 반환할 확률
 * @param declineRate            PG가 결제를 거절(한도 초과 등)할 확률
 * @param timeoutRate            PG가 승인은 해놓고 응답이 늦어 우리 쪽이 타임아웃 나는 확률 ("돈은 빠졌는데 우린 모름")
 * @param failAfterPgApproval    PG 승인 직후 우리 DB 반영이 실패하는 상황을 흉내 낸다
 */
@Component
public class ChaosSettings {

    public record Snapshot(long latencyMs, double failureRate, double declineRate, double timeoutRate,
                           boolean failAfterPgApproval) {
        public static final Snapshot NORMAL = new Snapshot(50, 0, 0, 0, false);
    }

    private volatile Snapshot current = Snapshot.NORMAL;

    public Snapshot get() {
        return current;
    }

    public void set(Snapshot snapshot) {
        this.current = snapshot;
    }

    public void reset() {
        this.current = Snapshot.NORMAL;
    }
}
