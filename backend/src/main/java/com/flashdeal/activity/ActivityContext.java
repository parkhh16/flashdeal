package com.flashdeal.activity;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 컨트롤러/예외 핸들러가 "이 요청의 활동 로그에 남길 정보"를 요청 속성에 적어두는 곳.
 * 필터가 응답 후 이 값을 읽어 로그 한 줄로 만든다. 요청 스레드가 아니면(배치, 테스트 도구) 조용히 무시한다.
 */
public final class ActivityContext {

    static final String DETAIL = "activity.detail";
    static final String USER_ID = "activity.userId";

    private ActivityContext() {
    }

    public static void put(String key, Object value) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null || value == null) return;
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) attrs.getAttribute(DETAIL, RequestAttributes.SCOPE_REQUEST);
        if (detail == null) {
            detail = new LinkedHashMap<>();
            attrs.setAttribute(DETAIL, detail, RequestAttributes.SCOPE_REQUEST);
        }
        detail.put(key, value);
    }

    /** 로그인처럼 인증 전 요청이라 SecurityContext에 사용자가 없을 때 사용자를 지정한다 */
    public static void userId(Long userId) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) attrs.setAttribute(USER_ID, userId, RequestAttributes.SCOPE_REQUEST);
    }
}
