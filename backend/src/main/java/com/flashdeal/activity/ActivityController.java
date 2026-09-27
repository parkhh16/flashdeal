package com.flashdeal.activity;

import com.flashdeal.activity.ActivityQueryService.*;
import com.flashdeal.auth.AuthUser;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Tag(name = "Activity")
@SecurityRequirement(name = "bearer")
@RestController
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityQueryService service;

    /**
     * 사용자용. URL에 userId가 있어도 토큰의 사용자와 다르면 403 (IDOR 방지).
     * 관리자라도 이 API로 남의 활동을 보지 못하게 했다. 관리자 조회는 /api/admin/activity 로 분리해서 권한 경계를 URL 단위로 명확히 한다.
     */
    @Operation(summary = "내 활동 (최근 검색어, 최근 본 상품)")
    @GetMapping("/api/users/{userId}/activity")
    public MyActivity myActivity(@AuthenticationPrincipal AuthUser user, @PathVariable Long userId) {
        if (!user.id().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "다른 사용자의 활동은 조회할 수 없습니다.");
        }
        return service.myActivity(userId);
    }

    @Operation(summary = "[관리자] 활동 로그", description = "사용자/기간/이벤트 유형/결과(성공·4xx·5xx) 필터, 최신순")
    @GetMapping("/api/admin/activity")
    public PageResponse<LogResponse> logs(@RequestParam(required = false) Long userId,
                                          @RequestParam(required = false) ActivityEventType type,
                                          @RequestParam(required = false) StatusClass status,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "30") int size) {
        return service.search(new Filter(userId, type, status, from, to), page, size);
    }

    @Operation(summary = "[관리자] 활동 요약", description = "총 요청 수, 주문 수, 에러율(5xx), 평균 응답시간, 유형별 건수")
    @GetMapping("/api/admin/activity/summary")
    public Summary summary(@RequestParam(required = false) Long userId,
                           @RequestParam(required = false) ActivityEventType type,
                           @RequestParam(required = false) StatusClass status,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return service.summary(new Filter(userId, type, status, from, to));
    }
}
