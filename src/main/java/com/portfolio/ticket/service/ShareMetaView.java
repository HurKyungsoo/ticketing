package com.portfolio.ticket.service;

import com.portfolio.ticket.config.KakaoShareProperties;
import com.portfolio.ticket.domain.Performance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDateTime;

/**
 * 공유 시트({@code fragment/sharesheet.html})가 필요로 하는 모델 값을 채운다.
 *
 * <p>두 값을 <b>한 번에</b> 넣는 게 요점이다. 시트는 {@code seo} 로 무엇을 공유할지 정하고
 * {@code kakaoJsKey} 로 카카오톡 옵션을 그릴지 정하는데, 한쪽만 넣으면 화면이 조용히
 * 반쪽으로 동작한다 — 키만 없으면 링크복사만 뜨고(정상), {@code seo} 만 없으면 시트는
 * 열리는데 공유할 내용이 비어 나간다. 결제 완료 화면은 실제 결제 경로와 테스트 경로
 * ({@code MockPaymentController}) 둘이 같은 템플릿을 그리므로, 그 둘이 각자 두 줄씩
 * 들고 있으면 한쪽만 고쳐질 자리가 된다.
 *
 * <p>baseUrl 을 컨트롤러에서 안 받는 이유: 공유 카드는 크롤러·카카오 서버가 바깥에서
 * 가져가므로 절대주소여야 하는데, Thymeleaf 3.1 부터 템플릿에서 {@code #request} 를 못 쓴다.
 * 요청 스레드 안에서만 호출되므로 여기서 직접 구해도 안전하다.
 */
@Component
@RequiredArgsConstructor
public class ShareMetaView {

    private final SeoView seoView;
    private final KakaoShareProperties kakaoShareProperties;

    /**
     * 화면에 공유 대상이 하나뿐일 때(예매 확정). 공유 대상은 예매가 아니라 공연이다
     * (이유는 {@link SeoView#forPerformanceShare} 주석 참고).
     *
     * @param showAt 예매한 회차 시각. 줄거리가 없는 공연의 대체 설명에 쓴다.
     */
    public void addPerformanceShare(Model model, Performance performance, LocalDateTime showAt) {
        model.addAttribute("seo", shareMetaOf(performance, showAt));
        addKakaoJsKey(model);
    }

    /**
     * 공유 대상이 여러 개인 화면(예매 내역)용. 행마다 payload 가 다르므로 모델에 바로 넣지 않고
     * 값을 돌려준다 — 화면이 행에 실어 두고, 팝업을 열 때 그 행의 값을 시트로 옮긴다.
     *
     * <p>화면에서 직접 만들지 않고 여기를 거치는 이유: 설명 문구와 절대주소 규칙이 세 화면
     * (상세·결제 완료·예매 내역)에서 갈리면 안 된다. 특히 이미지·링크는 카카오 서버가 바깥에서
     * 가져가므로 절대주소여야 하는데, 템플릿에서 만들면 상대경로가 조용히 섞여 들어간다.
     */
    public SeoView.Meta shareMetaOf(Performance performance, LocalDateTime showAt) {
        return seoView.forPerformanceShare(performance, showAt, baseUrl());
    }

    /**
     * 키가 없으면 null 이고 시트가 카카오톡 옵션을 아예 안 그린다 —
     * 눌러도 항상 실패하는 버튼을 보여줄 이유가 없다.
     */
    public void addKakaoJsKey(Model model) {
        model.addAttribute("kakaoJsKey",
                kakaoShareProperties.isConfigured() ? kakaoShareProperties.getJsKey() : null);
    }

    private String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
