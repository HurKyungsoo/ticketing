package com.portfolio.ticket.controller;

import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.security.CustomUserDetails;
import com.portfolio.ticket.service.SavedSearchService;
import com.portfolio.ticket.service.TooManySavedSearchesException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 목록 화면에서 "이 조건 저장"을 누를 때 쓰는 API. {@link com.portfolio.ticket.controller.WishlistApiController}
 * 와 같은 이유로 이 경로들은 {@code permitAll} 목록에 없어, 미인증이면 폼 로그인으로 302 된다.
 * 목록 화면은 저장 버튼을 로그인 상태에서만 그리므로(sec:authorize) 실제로는 로그인한
 * 사용자만 이 경로를 부른다.
 */
@RestController
@RequestMapping("/api/saved-searches")
@RequiredArgsConstructor
public class SavedSearchApiController {

    private final SavedSearchService savedSearchService;

    /**
     * 값이 없는 필드는 문자열 "all"(목록 화면 필터의 센티넬)이거나 빈 문자열일 수 있다 —
     * 서비스는 null 만 "전체"로 해석하므로 여기서 정리해 넘긴다.
     * category 는 {@link PerformanceCategory#name()} 문자열이다("all"이면 전체 장르).
     */
    public record SaveRequest(String category, String region, Integer month, String keyword) {}

    @PostMapping
    public ResponseEntity<?> save(@RequestBody SaveRequest request,
                                   @AuthenticationPrincipal CustomUserDetails principal) {
        PerformanceCategory category = parseCategory(request.category());
        String region = blankToNull(request.region());
        String keyword = blankToNull(request.keyword());

        var saved = savedSearchService.save(principal.getMemberId(), category, region, request.month(), keyword);
        return ResponseEntity.ok(Map.of("id", saved.getId(), "label", saved.getLabel()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails principal) {
        savedSearchService.delete(id, principal.getMemberId());
        return ResponseEntity.noContent().build();
    }

    /** "all"/빈 값/모르는 값은 전부 전체 장르(null)로 접는다 — 필터 센티넬이 그대로 넘어와도 안전하다. */
    private PerformanceCategory parseCategory(String value) {
        if (value == null || value.isBlank() || "all".equals(value)) {
            return null;
        }
        try {
            return PerformanceCategory.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank() || "all".equals(value)) ? null : value;
    }

    @ExceptionHandler(TooManySavedSearchesException.class)
    public ResponseEntity<?> handleTooMany(TooManySavedSearchesException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
