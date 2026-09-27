# LLM 자연어 검색: 환각과 장애가 검색 장애로 번지지 않게, 그리고 한글 오타 교정

## 목표

"5만원 이하 무선 마우스"처럼 말하듯이 검색하면 조건에 맞는 상품을 찾아 주는 기능입니다.

```
"5만원 이하 무선 마우스"  →  {category: MOUSE, maxPrice: 50000, keyword: "무선"}  →  DB 조회
```

## 설계 결정

| 결정 | 이유 |
|---|---|
| **LLM은 질의를 필터로 바꾸기만 하고, 상품은 DB에서 조회** | 없는 상품이나 가격을 지어내는 환각이 사용자에게 보일 경로가 없음 |
| **Structured Outputs** (Java 레코드 → JSON Schema) | 자유 텍스트를 파싱하다 실패할 일이 없음. 카테고리는 enum으로 강제 |
| **응답을 코드에서 한 번 더 검증** (`SearchFilter.sanitized()`) | 음수 가격, 최소 > 최대처럼 스키마로 못 막는 값을 교정 |
| **타임아웃 8초, 재시도 0회, 실패하면 규칙 기반 파서로 폴백** | 사용자가 기다리는 요청이라, LLM 장애가 검색 장애로 번지면 안 됨 |
| **Caffeine 캐시 + 스탬피드 방지** (`get(key, loader)`) | 같은 질의 20개가 동시에 와도 LLM 호출은 1번 |
| **폴백 결과는 캐시하지 않음** | LLM이 복구되면 바로 LLM 결과로 돌아오게 |
| **조건 칩을 빼면 목록 API로 재검색** | 조건 하나 빼려고 LLM을 다시 부르지 않음 (비용과 지연 절감) |

API 키가 없으면 모든 검색이 규칙 기반 파서로 처리됩니다. 규칙 기반 파서도 "3만원 이하", "10~30만원", 카테고리 단어, "마우"처럼 입력 중인 단어까지 인식합니다. "헤드셋"처럼 대분류보다 좁은 단어는 키워드로도 남겨서 같은 대분류의 스피커나 이어폰이 섞이지 않게 했습니다.

## 한글 오타 교정: "마으수" → "마우스"

### 문제

"마으수"로 검색하면 결과가 0건입니다. 음절 단위로 비교하면 3글자 중 2글자가 달라서 "마우스"와 가깝다고 보기 어렵습니다.

### 해결: 자모 단위 편집 거리

한글을 자모로 풀어서 비교하면 차이가 작아집니다.

```
마으수 → ㅁㅏㅇㅡㅅㅜ
마우스 → ㅁㅏㅇㅜㅅㅡ     → 모음 2개 차이
```

오교정을 줄이기 위해 범위를 좁혔습니다.

- **검색 결과가 0건일 때만** 교정합니다. 정상 검색에는 끼어들지 않습니다.
- 사전은 **카테고리 이름과 판매 중인 상품명의 단어**입니다. "우리 가게에 있는 말"로만 교정합니다.
- 여러 단어 중 틀린 단어만 고칩니다 ("게이밍 마으스" → "게이밍 마우스").
- 가까운 단어가 없으면 교정하지 않고, 엉뚱하게 전체 상품을 보여주지도 않습니다.
- 교정했다는 사실과 **"원래 검색어로 검색"** 링크를 화면에 함께 보여줍니다.

## 검증 범위

API 키가 없어서 실제 Claude API 호출은 검증하지 못했습니다. 제어 흐름(LLM 결과 → 캐시 → 장애 시 폴백, 스탬피드 방지)은 LLM 호출부를 스텁으로 바꾼 테스트로 검증했습니다.

## 관련 코드

- [`AiSearchService`](../../backend/src/main/java/com/flashdeal/ai/AiSearchService.java): 캐시, 폴백, 오타 교정 흐름
- [`ClaudeQueryParser`](../../backend/src/main/java/com/flashdeal/ai/ClaudeQueryParser.java): Structured Outputs
- [`RuleBasedQueryParser`](../../backend/src/main/java/com/flashdeal/ai/RuleBasedQueryParser.java): 규칙 기반 폴백
- [`SearchFilter`](../../backend/src/main/java/com/flashdeal/ai/SearchFilter.java): `sanitized()`
- [`TypoCorrector`](../../backend/src/main/java/com/flashdeal/ai/TypoCorrector.java): 자모 편집 거리
- 테스트: `AiSearchServiceTest`, `RuleBasedQueryParserTest`, `TypoSearchTest`
