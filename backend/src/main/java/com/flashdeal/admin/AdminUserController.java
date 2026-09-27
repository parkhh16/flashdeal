package com.flashdeal.admin;

import com.flashdeal.admin.AdminUserService.AdminUser;
import com.flashdeal.auth.AuthUser;
import com.flashdeal.auth.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Admin")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService service;

    @Operation(summary = "회원 목록", description = "아이디/이름/이메일 검색, 상태 필터. 주문 수 포함")
    @GetMapping
    public List<AdminUser> users(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) User.Status status) {
        return service.users(q, status);
    }

    @Operation(summary = "회원 정지/해제", description = "정지하면 로그인 중인 세션도 즉시 끊긴다")
    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeStatus(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id,
                             @RequestBody Map<String, User.Status> body) {
        service.changeStatus(admin.id(), id, body.get("status"));
    }

    @Operation(summary = "강제 로그아웃", description = "발급된 모든 토큰 무효화")
    @PostMapping("/{id}/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forceLogout(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        service.forceLogout(admin.id(), id);
    }

    @Operation(summary = "로그인 잠금 해제")
    @PostMapping("/{id}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id) {
        service.unlock(admin.id(), id);
    }
}
