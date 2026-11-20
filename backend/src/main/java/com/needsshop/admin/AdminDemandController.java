package com.needsshop.admin;

import com.needsshop.ai.DemandService;
import com.needsshop.ai.DemandService.DemandView;
import com.needsshop.ai.DemandService.Report;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 판매자 등록 추천: 손님이 검색했지만 찾지 못한 상품. /api/admin/** 는 ROLE_ADMIN만 접근 */
@Tag(name = "Admin")
@SecurityRequirement(name = "bearer")
@Validated
@RestController
@RequestMapping("/api/admin/demand")
@RequiredArgsConstructor
public class AdminDemandController {

    private final DemandService demandService;

    @Operation(summary = "못 찾은 검색어와 가장 최근 AI 정리 결과",
            description = "못 찾은 검색어 집계는 AI 없이 코드가 센다. AI 정리는 하루 한 번 또는 POST로 만든다")
    @GetMapping
    public DemandView view(@RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return demandService.view(days);
    }

    @Operation(summary = "지금 AI로 다시 정리", description = "Gemini 응답을 기다리므로 수 초 걸린다. 키가 없으면 409")
    @PostMapping("/report")
    public Report generate(@RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return demandService.generate(days);
    }
}
