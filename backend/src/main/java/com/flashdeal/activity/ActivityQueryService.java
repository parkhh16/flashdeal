package com.flashdeal.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashdeal.auth.User;
import com.flashdeal.auth.UserRepository;
import com.flashdeal.product.ProductDtos.ProductResponse;
import com.flashdeal.product.ProductRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityQueryService {

    private final ActivityLogRepository repository;
    private final ActivityRecorder recorder;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public enum StatusClass {SUCCESS, CLIENT_ERROR, SERVER_ERROR}

    public record Filter(Long userId, ActivityEventType type, StatusClass status, LocalDateTime from, LocalDateTime to) {
    }

    public record LogResponse(Long id, Long userId, String userName, ActivityEventType eventType, String eventLabel,
                              String method, String target, int statusCode, int latencyMs, String requestId,
                              Map<String, Object> detail, LocalDateTime createdAt) {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    public record Summary(long totalRequests, long orders, long clientErrors, long serverErrors, double errorRate,
                          double avgLatencyMs, Map<ActivityEventType, Long> byType, long droppedLogs) {
    }


    public record MyActivity(List<String> recentSearches, List<ProductResponse> recentlyViewed) {
    }

    public PageResponse<LogResponse> search(Filter filter, int page, int size) {
        recorder.flush(); // 방금 발생한 요청도 바로 보이게
        Page<ActivityLog> logs = repository.findAll(spec(filter),
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "id")));
        // 페이지에 등장한 사용자 이름을 IN 쿼리 한 번으로 가져온다 (로그마다 조회하면 N+1)
        Set<Long> userIds = logs.stream().map(ActivityLog::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        List<LogResponse> content = logs.stream().map(l -> new LogResponse(l.getId(), l.getUserId(),
                names.get(l.getUserId()), l.getEventType(), l.getEventType().getLabel(), l.getMethod(), l.getTarget(),
                l.getStatusCode(), l.getLatencyMs(), l.getRequestId(), parse(l.getDetail()), l.getCreatedAt())).toList();
        return new PageResponse<>(content, logs.getNumber(), logs.getSize(), logs.getTotalElements(), logs.getTotalPages());
    }

    /** 요약 카드. 건수/평균은 DB에서 집계한다 (로그를 전부 가져와 자바에서 세지 않는다) */
    public Summary summary(Filter filter) {
        recorder.flush();
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select event_type, count(*) as cnt,
                       sum(case when status_code between 400 and 499 then 1 else 0 end) as client_errors,
                       sum(case when status_code >= 500 then 1 else 0 end) as server_errors,
                       sum(latency_ms) as latency_sum
                from activity_log""" + where + " group by event_type", params);
        long total = 0, client = 0, server = 0, latency = 0, orders = 0;
        Map<ActivityEventType, Long> byType = new EnumMap<>(ActivityEventType.class);
        for (Map<String, Object> r : rows) {
            ActivityEventType type = ActivityEventType.valueOf((String) r.get("event_type"));
            long cnt = num(r.get("cnt"));
            byType.put(type, cnt);
            total += cnt;
            client += num(r.get("client_errors"));
            server += num(r.get("server_errors"));
            latency += num(r.get("latency_sum"));
            if (type == ActivityEventType.ORDER_CREATE) orders += cnt - num(r.get("client_errors")) - num(r.get("server_errors"));
        }
        return new Summary(total, orders, client, server, total == 0 ? 0 : (double) server / total,
                total == 0 ? 0 : (double) latency / total, byType, recorder.droppedCount());
    }

    /** 사용자용: 개발자 정보(상태코드, 지연시간, 파서 종류)는 빼고 사용자 언어로 된 정보만 */
    public MyActivity myActivity(Long userId) {
        recorder.flush();
        List<String> searches = repository.findRecent(userId, ActivityEventType.SEARCH, PageRequest.of(0, 50)).stream()
                .map(l -> parse(l.getDetail()).get("q"))
                .filter(Objects::nonNull).map(Object::toString)
                .distinct().limit(8).toList();

        List<Long> viewedIds = repository.findRecent(userId, ActivityEventType.PRODUCT_VIEW, PageRequest.of(0, 50)).stream()
                .map(l -> parse(l.getDetail()).get("productId"))
                .filter(Objects::nonNull).map(v -> ((Number) v).longValue())
                .distinct().limit(8).toList();
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, ProductResponse> products = productRepository.findAllById(viewedIds).stream()
                .filter(p -> p.isActive())
                .collect(Collectors.toMap(p -> p.getId(), p -> ProductResponse.from(p, now), (a, b) -> a));
        List<ProductResponse> viewed = viewedIds.stream().map(products::get).filter(Objects::nonNull).toList();
        return new MyActivity(searches, viewed);
    }

    private Specification<ActivityLog> spec(Filter f) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (f.userId() != null) p.add(cb.equal(root.get("userId"), f.userId()));
            if (f.type() != null) p.add(cb.equal(root.get("eventType"), f.type()));
            if (f.from() != null) p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
            if (f.to() != null) p.add(cb.lessThan(root.get("createdAt"), f.to()));
            if (f.status() != null) {
                switch (f.status()) {
                    case SUCCESS -> p.add(cb.lessThan(root.get("statusCode"), 400));
                    case CLIENT_ERROR -> p.add(cb.between(root.get("statusCode"), 400, 499));
                    case SERVER_ERROR -> p.add(cb.greaterThanOrEqualTo(root.get("statusCode"), 500));
                }
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    private String where(Filter f, MapSqlParameterSource params) {
        List<String> c = new ArrayList<>();
        Function<String, String> add = s -> { c.add(s); return s; };
        if (f.userId() != null) { add.apply("user_id = :userId"); params.addValue("userId", f.userId()); }
        if (f.type() != null) { add.apply("event_type = :type"); params.addValue("type", f.type().name()); }
        if (f.from() != null) { add.apply("created_at >= :from"); params.addValue("from", f.from()); }
        if (f.to() != null) { add.apply("created_at < :to"); params.addValue("to", f.to()); }
        if (f.status() != null) {
            add.apply(switch (f.status()) {
                case SUCCESS -> "status_code < 400";
                case CLIENT_ERROR -> "status_code between 400 and 499";
                case SERVER_ERROR -> "status_code >= 500";
            });
        }
        return c.isEmpty() ? "" : " where " + String.join(" and ", c);
    }

    private Map<String, Object> parse(String json) {
        if (json == null) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
