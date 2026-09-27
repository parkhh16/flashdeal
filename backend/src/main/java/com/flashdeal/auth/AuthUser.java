package com.flashdeal.auth;

/**
 * SecurityContext에 들어가는 인증 주체. 매 요청마다 DB를 조회하지 않도록 토큰 클레임만으로 만든다.
 */
public record AuthUser(Long id, String email, User.Role role) {
}
