package com.flashdeal.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Idempotency-Key 기반 멱등 처리. 따닥 클릭, 네트워크 재전송, 클라이언트 재시도로 주문과 결제가 두 번 생성되는 것을 막는다.
 *
 * 1. (user, key)로 IN_PROGRESS 레코드 INSERT → 유니크 제약이 동시 요청 중 하나만 통과시킨다 (앱 락 불필요, 다중 서버에서도 동작)
 * 2. 통과한 요청만 실제 로직 실행 → COMPLETED + 응답 저장
 * 3. 이후 같은 키 → 저장된 응답을 그대로 재생 (Idempotent-Replayed: true)
 * 4. 로직이 예외로 끝나면 레코드를 지워서 클라이언트가 같은 키로 재시도할 수 있게 한다
 */
@Slf4j
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> ResponseEntity<T> execute(Long userId, String scope, String key, Object request,
                                         Class<T> responseType, Supplier<ResponseEntity<T>> action) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISSING);
        }
        String scopedKey = scope + ":" + key;
        String requestHash = hash(scope + toJson(request));

        Long recordId;
        try {
            recordId = requiresNew.execute(s ->
                    repository.saveAndFlush(new IdempotencyRecord(userId, scopedKey, requestHash)).getId());
        } catch (DataIntegrityViolationException duplicated) {
            return replay(userId, scopedKey, requestHash, responseType);
        }

        try {
            ResponseEntity<T> response = action.get();
            requiresNew.executeWithoutResult(s -> repository.findById(recordId).ifPresent(r ->
                    r.complete(response.getStatusCode().value(), toJson(response.getBody()))));
            return response;
        } catch (RuntimeException e) {
            requiresNew.executeWithoutResult(s -> repository.deleteById(recordId));
            throw e;
        }
    }

    private <T> ResponseEntity<T> replay(Long userId, String scopedKey, String requestHash, Class<T> type) {
        IdempotencyRecord record = repository.findByUserIdAndIdempotencyKey(userId, scopedKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
        if (!record.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }
        log.info("event=IDEMPOTENT_REPLAY key={}", scopedKey);
        try {
            return ResponseEntity.status(record.getResponseStatus())
                    .header("Idempotent-Replayed", "true")
                    .body(objectMapper.readValue(record.getResponseBody(), type));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
