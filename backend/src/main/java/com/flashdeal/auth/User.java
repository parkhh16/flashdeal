package com.flashdeal.auth;

import com.flashdeal.common.BaseTimeEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    public static final int MAX_LOGIN_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_id", unique = true, length = 30)
    private String loginId;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /** 토큰에 이 값을 넣어두고, 요청마다 비교한다. 증가시키면 이미 발급된 토큰이 전부 무효가 된다 */
    @Column(nullable = false)
    private int tokenVersion;

    @Column(nullable = false)
    private int failedLoginCount;

    private LocalDateTime lockedUntil;

    private LocalDateTime lastLoginAt;

    private LocalDateTime withdrawnAt;

    public enum Role {USER, ADMIN}

    public enum Status {ACTIVE, SUSPENDED, WITHDRAWN}

    public User(String email, String encodedPassword, String name, Role role) {
        this(deriveLoginId(email), email, encodedPassword, name, role);
    }

    public User(String loginId, String email, String encodedPassword, String name, Role role) {
        this.loginId = loginId;
        this.email = email;
        this.password = encodedPassword;
        this.name = name;
        this.role = role;
    }

    /** 이메일 앞부분으로 아이디를 만든다 (아이디 규칙: 영문 소문자·숫자·_ 최대 20자) */
    private static String deriveLoginId(String email) {
        String head = email.substring(0, Math.max(1, email.indexOf('@'))).toLowerCase().replaceAll("[^a-z0-9_]", "");
        return head.substring(0, Math.min(20, head.length()));
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isLocked(LocalDateTime now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public void loginSucceeded(LocalDateTime now) {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
    }

    /** 연속 5회 실패하면 5분 잠근다. 잠금이 풀린 뒤에는 카운트를 다시 센다 */
    public void loginFailed(LocalDateTime now) {
        this.failedLoginCount++;
        if (failedLoginCount >= MAX_LOGIN_FAILURES) {
            this.lockedUntil = now.plus(LOCK_DURATION);
            this.failedLoginCount = 0;
        }
    }

    public void unlock() {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    /** 모든 기기에서 로그아웃: 지금까지 발급된 토큰을 전부 무효화 */
    public void revokeTokens() {
        this.tokenVersion++;
    }

    public void suspend() {
        this.status = Status.SUSPENDED;
        revokeTokens();
    }

    public void activate() {
        this.status = Status.ACTIVE;
        unlock();
    }

    /**
     * 탈퇴: 행을 지우지 않고(주문·결제 기록의 법정 보관 의무, 외래 참조) 개인정보만 파기한다.
     * 아이디/이메일을 비워서 같은 아이디·이메일로 재가입할 수 있게 하고, 비밀번호는 아무도 모르는 값으로 바꾼다.
     */
    public void withdraw(LocalDateTime now, String unusablePasswordHash) {
        this.status = Status.WITHDRAWN;
        this.withdrawnAt = now;
        this.loginId = null;
        this.email = "withdrawn-" + id + "-" + UUID.randomUUID().toString().substring(0, 8) + "@deleted.invalid";
        this.name = "탈퇴회원";
        this.password = unusablePasswordHash;
        revokeTokens();
    }

    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
