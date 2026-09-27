package com.flashdeal.auth;

import com.flashdeal.auth.AuthService.Me;
import com.flashdeal.auth.AuthService.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Auth")
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    public record SignupRequest(@NotBlank @Size(min = 4, max = 20) String loginId,
                                @NotBlank @Size(min = 8, max = 64) String password,
                                @NotBlank @Size(max = 50) String name,
                                @NotBlank @Email @Size(max = 100) String email) {
    }

    /** loginId에는 아이디 또는 이메일을 넣을 수 있다 */
    public record LoginRequest(@NotBlank String loginId, @NotBlank String password) {
    }

    public record WithdrawRequest(@NotBlank String password) {
    }

    @Operation(summary = "회원가입", description = "아이디: 영문 소문자·숫자·_ 4~20자 / 비밀번호: 영문+숫자 8자 이상")
    @PostMapping("/api/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(new AuthService.Signup(request.loginId(), request.password(), request.name(), request.email()));
    }

    @Operation(summary = "아이디 사용 가능 여부")
    @GetMapping("/api/auth/check-login-id")
    public Map<String, Boolean> checkLoginId(@RequestParam String value) {
        return Map.of("available", authService.isLoginIdAvailable(value.trim().toLowerCase()));
    }

    @Operation(summary = "로그인", description = "5회 연속 실패 시 5분 잠금")
    @PostMapping("/api/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.loginId(), request.password());
    }

    @Operation(summary = "내 정보")
    @SecurityRequirement(name = "bearer")
    @GetMapping("/api/me")
    public Me me(@AuthenticationPrincipal AuthUser user) {
        return authService.me(user.id());
    }

    @Operation(summary = "모든 기기에서 로그아웃", description = "지금까지 발급된 토큰을 전부 무효화")
    @SecurityRequirement(name = "bearer")
    @PostMapping("/api/me/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutEverywhere(@AuthenticationPrincipal AuthUser user) {
        authService.logoutEverywhere(user.id());
    }

    @Operation(summary = "회원 탈퇴", description = "비밀번호 확인 필요. 진행 중인 주문이 있으면 불가. 개인정보는 파기하고 주문 기록은 보관")
    @SecurityRequirement(name = "bearer")
    @DeleteMapping("/api/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody WithdrawRequest request) {
        authService.withdraw(user.id(), request.password());
    }
}
