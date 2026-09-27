package com.flashdeal.payment;

import com.flashdeal.common.config.FlashDealProperties;
import com.flashdeal.mockpg.MockPgController.ApproveRequest;
import com.flashdeal.mockpg.MockPgController.PgPayment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

/**
 * 외부 PG 호출 원칙
 * 1. 타임아웃은 필수다. 없으면 PG가 느려질 때 우리 스레드가 전부 묶여서 같이 죽는다.
 * 2. 재시도는 PG가 paymentKey로 멱등성을 보장하기 때문에 안전하다. 같은 키로 여러 번 보내도 한 번만 결제된다.
 * 3. 재시도를 다 써도 응답이 없으면 실패가 아니라 UNKNOWN이다. 실제로는 승인됐을 수 있으므로 대사로 확인한다.
 */
@Slf4j
@Component
public class HttpPgClient implements PgClient {

    private final FlashDealProperties.Pg config;
    private volatile int serverPort = 8080;
    private volatile RestClient restClient;

    public HttpPgClient(FlashDealProperties properties) {
        this.config = properties.pg();
    }

    @Override
    public PgResult approve(String paymentKey, String orderNo, long amount) {
        PgResult last = PgResult.unknown("not attempted");
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            try {
                PgPayment body = client().post()
                        .body(new ApproveRequest(paymentKey, orderNo, amount))
                        .retrieve()
                        .body(PgPayment.class);
                return new PgResult(Outcome.APPROVED, body.transactionId(), null);
            } catch (HttpClientErrorException e) {
                // 4xx는 PG가 명확하게 거절한 것이므로 재시도하지 않는다
                if (e.getStatusCode() == HttpStatus.PAYMENT_REQUIRED) {
                    PgPayment body = e.getResponseBodyAs(PgPayment.class);
                    return new PgResult(Outcome.DECLINED, null, body != null ? body.reason() : "DECLINED");
                }
                return new PgResult(Outcome.DECLINED, null, "PG_CLIENT_ERROR_" + e.getStatusCode().value());
            } catch (RestClientException e) {
                // 5xx, 타임아웃(연결/응답 본문 읽기 중 포함)은 결과를 알 수 없다. 지수 백오프 후 같은 paymentKey로 재시도
                last = PgResult.unknown(e.getClass().getSimpleName());
                log.warn("event=PG_APPROVE_RETRY paymentKey={} attempt={} cause={}", paymentKey, attempt, e.getMessage());
                sleep(200L * (1L << (attempt - 1)));
            }
        }
        log.error("event=PG_APPROVE_UNKNOWN paymentKey={} cause={}", paymentKey, last.reason());
        return last;
    }

    @Override
    public PgResult inquire(String paymentKey) {
        try {
            PgPayment body = client().get().uri("/{key}", paymentKey).retrieve().body(PgPayment.class);
            // 조회 API는 거절/취소 건도 200으로 준다. HTTP 상태가 아니라 본문의 결제 상태로 판단해야 한다
            // (예전에는 200이면 무조건 승인으로 봐서, 거절 직후 서버가 죽은 건을 대사가 승인으로 오판할 수 있었다)
            return toResult(body);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return new PgResult(Outcome.NOT_FOUND, null, "NOT_FOUND");
            }
            PgPayment body = e.getResponseBodyAs(PgPayment.class);
            return new PgResult(Outcome.DECLINED, null, body != null ? body.reason() : "DECLINED");
        } catch (RestClientException e) {
            return PgResult.unknown(e.getClass().getSimpleName());
        }
    }

    @Override
    public PgResult cancel(String paymentKey) {
        try {
            return toResult(client().post().uri("/{key}/cancel", paymentKey).retrieve().body(PgPayment.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return new PgResult(Outcome.NOT_FOUND, null, "NOT_FOUND");
            }
            return new PgResult(Outcome.DECLINED, null, "PG_CLIENT_ERROR_" + e.getStatusCode().value());
        } catch (RestClientException e) {
            return PgResult.unknown(e.getClass().getSimpleName());
        }
    }

    private PgResult toResult(PgPayment body) {
        if (body == null || body.status() == null) return PgResult.unknown("EMPTY_BODY");
        return switch (body.status()) {
            case "APPROVED" -> new PgResult(Outcome.APPROVED, body.transactionId(), null);
            case "CANCELLED" -> new PgResult(Outcome.CANCELLED, body.transactionId(), null);
            case "DECLINED" -> new PgResult(Outcome.DECLINED, null, body.reason());
            default -> PgResult.unknown("UNKNOWN_STATUS_" + body.status());
        };
    }

    /**
     * base-url을 비워두면 같은 앱에 내장된 Mock PG를 호출한다.
     * 이때 포트는 서버가 뜬 뒤에야 확정되므로(테스트의 RANDOM_PORT 등) 기동 이벤트에서 받아온다.
     */
    @EventListener
    public void onServerStarted(WebServerInitializedEvent event) {
        this.serverPort = event.getWebServer().getPort();
    }

    private RestClient client() {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(config.connectTimeout());
                    factory.setReadTimeout(config.readTimeout());
                    String base = StringUtils.hasText(config.baseUrl())
                            ? config.baseUrl()
                            : "http://localhost:" + serverPort + "/mock-pg";
                    restClient = RestClient.builder().baseUrl(base + "/v1/payments").requestFactory(factory).build();
                }
            }
        }
        return restClient;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
