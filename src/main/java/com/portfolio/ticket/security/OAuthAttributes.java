package com.portfolio.ticket.security;

import com.portfolio.ticket.domain.AuthProvider;

import java.util.Map;

/**
 * 카카오/네이버 응답 구조가 서로 달라서 registrationId 별로 파싱 방법을 분리한다.
 * 네이버는 실제 사용자 정보가 최상위가 아니라 "response" 하위에 한 번 더 감싸여 온다.
 */
public record OAuthAttributes(AuthProvider provider, String providerId, String nickname) {

    public static OAuthAttributes of(String registrationId, Map<String, Object> attributes) {
        if ("naver".equals(registrationId)) {
            return ofNaver(attributes);
        }
        return ofKakao(attributes);
    }

    @SuppressWarnings("unchecked")
    private static OAuthAttributes ofNaver(Map<String, Object> attributes) {
        Map<String, Object> response = (Map<String, Object>) attributes.get("response");
        String id = String.valueOf(response.get("id"));
        String name = (String) response.get("name");
        return new OAuthAttributes(AuthProvider.NAVER, id, name != null ? name : "네이버사용자" + id);
    }

    @SuppressWarnings("unchecked")
    private static OAuthAttributes ofKakao(Map<String, Object> attributes) {
        String id = String.valueOf(attributes.get("id"));
        Map<String, Object> kakaoAccount = (Map<String, Object>) attributes.get("kakao_account");
        Map<String, Object> profile = kakaoAccount != null ? (Map<String, Object>) kakaoAccount.get("profile") : null;
        String nickname = profile != null ? (String) profile.get("nickname") : null;
        return new OAuthAttributes(AuthProvider.KAKAO, id, nickname != null ? nickname : "카카오사용자" + id);
    }
}
