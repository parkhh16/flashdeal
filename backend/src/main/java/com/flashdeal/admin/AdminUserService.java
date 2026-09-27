package com.flashdeal.admin;

import com.flashdeal.auth.User;
import com.flashdeal.auth.UserAuthCache;
import com.flashdeal.auth.UserRepository;
import com.flashdeal.common.error.BusinessException;
import com.flashdeal.common.error.ErrorCode;
import com.flashdeal.order.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 관리자 회원 관리. 정지/강제 로그아웃은 토큰 버전을 올리고 캐시를 비워서 "지금 로그인해 있는 사용자"에게도 즉시 적용된다.
 * 관리자 계정끼리는 서로 정지할 수 없다 (마지막 관리자가 잠기는 사고 방지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final UserAuthCache userAuthCache;
    private final Clock clock;

    public record AdminUser(Long id, String loginId, String name, String email, User.Role role, User.Status status,
                            boolean locked, int failedLoginCount, LocalDateTime lastLoginAt, LocalDateTime createdAt,
                            LocalDateTime withdrawnAt, long orderCount) {
    }

    @Transactional(readOnly = true)
    public List<AdminUser> users(String query, User.Status status) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<User> users = userRepository.findAll(Sort.by("id")).stream()
                .filter(u -> status == null || u.getStatus() == status)
                .filter(u -> query == null || query.isBlank() || matches(u, query.trim().toLowerCase()))
                .toList();
        // 회원별 주문 수는 IN + GROUP BY 한 번으로 (회원마다 count 쿼리를 날리면 N+1)
        Map<Long, Long> orderCounts = users.isEmpty() ? Map.of() : orderRepository
                .countByUserIds(users.stream().map(User::getId).toList()).stream()
                .collect(Collectors.toMap(OrderRepository.UserOrderCount::getUserId, OrderRepository.UserOrderCount::getCount));
        return users.stream().map(u -> new AdminUser(u.getId(), u.getLoginId(), u.getName(), u.getEmail(), u.getRole(),
                u.getStatus(), u.isLocked(now), u.getFailedLoginCount(), u.getLastLoginAt(), u.getCreatedAt(),
                u.getWithdrawnAt(), orderCounts.getOrDefault(u.getId(), 0L))).toList();
    }

    @Transactional
    public void changeStatus(Long adminId, Long userId, User.Status status) {
        User user = manageable(adminId, userId);
        switch (status) {
            case SUSPENDED -> user.suspend();
            case ACTIVE -> user.activate();
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT, "정상 또는 정지 상태로만 바꿀 수 있습니다.");
        }
        userAuthCache.evict(userId);
        log.info("event=USER_STATUS_CHANGED userId={} status={} by={}", userId, status, adminId);
    }

    @Transactional
    public void forceLogout(Long adminId, Long userId) {
        manageable(adminId, userId).revokeTokens();
        userAuthCache.evict(userId);
        log.info("event=USER_FORCE_LOGOUT userId={} by={}", userId, adminId);
    }

    @Transactional
    public void unlock(Long adminId, Long userId) {
        manageable(adminId, userId).unlock();
    }

    private User manageable(Long adminId, Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getId().equals(adminId) || user.getRole() == User.Role.ADMIN) {
            throw new BusinessException(ErrorCode.INVALID_USER_OPERATION, "관리자 계정은 여기서 관리할 수 없습니다.");
        }
        if (user.getStatus() == User.Status.WITHDRAWN) {
            throw new BusinessException(ErrorCode.INVALID_USER_OPERATION, "탈퇴한 회원입니다.");
        }
        return user;
    }

    private static boolean matches(User u, String q) {
        return (u.getLoginId() != null && u.getLoginId().contains(q))
                || u.getName().toLowerCase().contains(q)
                || u.getEmail().toLowerCase().contains(q);
    }
}
