package com.flashdeal.ai;

import com.flashdeal.ai.ProductTagger.Checked;
import com.flashdeal.common.cluster.ClusterLock;
import com.flashdeal.product.Product;
import com.flashdeal.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 태그가 없는 상품(새 상품, 이름·설명이 바뀐 상품)에 AI 태그를 붙이는 백그라운드 작업.
 *
 * <p>상품 등록 요청 안에서 AI를 부르지 않는다. Gemini 응답이 7초 넘게 걸리기도 해서, 관리자가 저장 버튼을 누르고 기다리게 된다.
 * 저장은 바로 끝내고 이 작업이 주기적으로 채운다. 실패하면(네트워크, 한도 초과) 그대로 두고 다음 주기에 다시 시도한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductTagJob {

    private static final int CHUNK = 10;

    private final ProductRepository products;
    private final ProductTagger tagger;
    private final ClusterLock clusterLock;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** 서버가 여러 대면 한 서버에서만 실행한다 (같은 상품에 AI를 두 번 부르지 않게) */
    @Scheduled(initialDelayString = "${flashdeal.scheduler.tagging-initial-delay:10s}",
            fixedDelayString = "${flashdeal.scheduler.tagging-interval:1m}")
    public void scheduled() {
        if (!tagger.isEnabled()) return;
        clusterLock.runExclusively("product-tagging", Duration.ofMinutes(10), this::tagPending);
    }

    /** @return 태그를 저장한 상품 수 */
    public int tagPending() {
        List<Product> pending = tx.execute(s -> products.findUntagged(PageRequest.of(0, CHUNK)));
        int saved = 0;
        for (Product p : pending == null ? List.<Product>of() : pending) {
            String seen = p.contentText();
            Optional<Checked> checked;
            try {
                checked = tagger.tag(p); // 트랜잭션 밖에서 부른다 (응답을 기다리는 동안 DB 커넥션을 쥐고 있지 않게)
            } catch (RuntimeException e) {
                log.warn("event=PRODUCT_TAG_FAILED productId={} cause={}", p.getId(), e.toString());
                continue;
            }
            // 모델이 답을 못 주면(안전 차단 등) 빈 태그로 끝낸다. 같은 내용으로 매 주기 다시 부르지 않게
            List<String> tags = checked.map(Checked::kept).orElse(List.of());
            if (save(p.getId(), seen, tags)) {
                saved++;
                log.info("event=PRODUCT_TAGGED productId={} tags={} dropped={}", p.getId(), tags,
                        checked.map(Checked::dropped).orElse(java.util.Map.of()));
            }
        }
        return saved;
    }

    /** 그사이 관리자가 상품을 고쳤으면 저장하지 않는다. 고친 내용으로 다음 주기에 다시 만든다 */
    private boolean save(Long id, String seenContent, List<String> tags) {
        Boolean ok = tx.execute(s -> products.findById(id)
                .filter(fresh -> fresh.contentText().equals(seenContent))
                .map(fresh -> {
                    fresh.applyTags(tags, LocalDateTime.now(clock));
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(ok);
    }
}
