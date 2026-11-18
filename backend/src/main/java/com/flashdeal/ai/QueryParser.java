package com.flashdeal.ai;

import java.util.Optional;

/**
 * 자연어 검색어를 SearchFilter로 바꾸는 LLM 호출부. 공급자(Gemini 등)를 바꿔도 AiSearchService는 그대로 둔다.
 */
public interface QueryParser {

    /** API 키가 설정돼 있는지. false면 호출하지 않고 바로 규칙 기반 파서를 쓴다 */
    boolean isEnabled();

    /**
     * @return 쓸 수 있는 출력이 없으면(빈 응답, 깨진 JSON, 안전 차단) empty.
     *         네트워크 오류, 타임아웃, 4xx/5xx는 예외로 던져서 호출자가 폴백한다
     */
    Optional<SearchFilter> parse(String query);
}
