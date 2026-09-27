package com.flashdeal.auth;

import com.flashdeal.activity.ActivityContext;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.OrderRepository;
import com.flashdeal.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final Pattern LOGIN_ID = Pattern.compile("^[a-z0-9_]{4,20}$");
    private static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final UserAuthCache userAuthCache;
    private final Clock clock;

    public record Signup(String loginId, String password, String name, String email) {
    }

    public record TokenResponse(String accessToken, Long userId, String loginId, String name, String role) {
    }

    public record Me(Long id, String loginId, String name, String email, String role, LocalDateTime createdAt,
                     LocalDateTime lastLoginAt) {
    }

    public boolean isLoginIdAvailable(String loginId) {
        return LOGIN_ID.matcher(loginId).matches() && !userRepository.existsByLoginId(loginId);
    }

    /**
     * 중복 확인(exists) → 저장 사이에 같은 아이디로 동시에 가입하면 둘 다 확인을 통과할 수 있다.
     * 최종 방어선은 DB 유니크 제약이고, 위반 예외를 "이미 사용 중" 응답으로 바꿔준다.
     */
    @Transactional
    public TokenResponse signup(Signup s) {
        String loginId = s.loginId().trim().toLowerCase();
        if (!LOGIN_ID.matcher(loginId).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "아이디는 영문 소문자, 숫자, _ 조합 4~20자여야 합니다.");
        }
        if (!PASSWORD.matcher(s.password()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "비밀번호는 영문과 숫자를 포함해 8자 이상이어야 합니다.");
        }
        if (userRepository.existsByLoginId(loginId)) throw new BusinessException(ErrorCode.LOGIN_ID_DUPLICATED);
        if (userRepository.existsByEmail(s.email())) throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        User user;
        try {
            user = userRepository.saveAndFlush(new User(loginId, s.email(), passwordEncoder.encode(s.password()),
                    s.name().trim(), User.Role.USER));
        } catch (DataIntegrityViolationException e) {
            // 제약 위반 뒤에는 세션이 깨져 있어서 다시 조회할 수 없다. 어떤 유니크 제약이 걸렸는지는 메시지로 판단한다
            String message = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase();
            throw new BusinessException(message.contains("login_id")
                    ? ErrorCode.LOGIN_ID_DUPLICATED : ErrorCode.EMAIL_DUPLICATED);
        }
        log.info("event=SIGNUP userId={} loginId={}", user.getId(), loginId);
        return issue(user);
    }

    /**
     * 실패 횟수는 예외를 던진 뒤에도 남아야 하므로 BusinessException에 롤백하지 않는다.
     * 아이디가 없든 비밀번호가 틀리든 같은 메시지를 줘서 계정 존재 여부를 노출하지 않는다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public TokenResponse login(String identifier, String password) {
        LocalDateTime now = LocalDateTime.now(clock);
        String id = identifier.trim();
        ActivityContext.put("loginId", mask(id));
        User user = (id.contains("@") ? userRepository.findByEmail(id) : userRepository.findByLoginId(id.toLowerCase()))
                .filter(u -> u.getStatus() != User.Status.WITHDRAWN)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));
        ActivityContext.userId(user.getId());

        if (user.isLocked(now)) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            user.loginFailed(now);
            if (user.isLocked(now)) {
                log.warn("event=LOGIN_LOCKED userId={}", user.getId());
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            }
            throw new BusinessException(ErrorCode.LOGIN_FAILED,
                    "아이디 또는 비밀번호가 올바르지 않습니다. (%d회 더 틀리면 5분간 잠깁니다)"
                            .formatted(User.MAX_LOGIN_FAILURES - user.getFailedLoginCount()));
        }
        // 정지 여부는 비밀번호가 맞은 뒤에 알려준다 (남의 계정 상태를 추측할 수 없게)
        if (user.getStatus() == User.Status.SUSPENDED) {
            throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
        }
        user.loginSucceeded(now);
        return issue(user);
    }

    @Transactional(readOnly = true)
    public Me me(Long userId) {
        User u = find(userId);
        return new Me(u.getId(), u.getLoginId(), u.getName(), u.getEmail(), u.getRole().name(), u.getCreatedAt(),
                u.getLastLoginAt());
    }

    /** 모든 기기에서 로그아웃 */
    @Transactional
    public void logoutEverywhere(Long userId) {
        find(userId).revokeTokens();
        userAuthCache.evict(userId);
    }

    /**
     * 회원 탈퇴. 결제 대기·결제 확인 중인 주문이 있으면 막는다 (돈과 재고가 걸린 상태를 주인 없이 남기지 않기 위해).
     * 탈퇴 즉시 토큰 버전이 올라가서, 이미 발급된 토큰으로는 더 이상 요청할 수 없다.
     */
    @Transactional
    public void withdraw(Long userId, String password) {
        User user = find(userId);
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_MISMATCH);
        }
        if (user.getRole() == User.Role.ADMIN) {
            throw new BusinessException(ErrorCode.INVALID_USER_OPERATION, "관리자 계정은 탈퇴할 수 없습니다.");
        }
        long inProgress = orderRepository.countByUserIdAndStatusIn(userId,
                EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYING));
        if (inProgress > 0) {
            throw new BusinessException(ErrorCode.WITHDRAW_BLOCKED);
        }
        user.withdraw(LocalDateTime.now(clock), passwordEncoder.encode(UUID.randomUUID().toString()));
        userAuthCache.evict(userId);
        log.info("event=WITHDRAW userId={}", userId);
    }

    private TokenResponse issue(User user) {
        return new TokenResponse(jwtProvider.issue(user), user.getId(), user.getLoginId(), user.getName(),
                user.getRole().name());
    }

    private User find(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /** 로그에 원본 아이디/이메일을 남기지 않는다: user1 → us***, user1@flashdeal.com → us***@flashdeal.com */
    static String mask(String value) {
        int at = value.indexOf('@');
        String head = at < 0 ? value : value.substring(0, at);
        String tail = at < 0 ? "" : value.substring(at);
        return head.substring(0, Math.min(2, head.length())) + "***" + tail;
    }
}
