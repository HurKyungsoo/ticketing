package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.repository.*;
import com.portfolio.ticket.service.SeoView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공유 시트({@code fragment/sharesheet.html}).
 *
 * <p>공연 상세와 결제 완료가 같은 조각을 쓴다. 조각으로 묶으면서 <b>화면으로는 안 보이는
 * 결함</b>을 하나 만들었다가 잡았고, 그게 이 테스트가 있는 이유다 — 아래
 * {@link #doesNotLoadKakaoSdkWithoutKey} 주석 참고.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShareSheetTest {

    private static final String KAKAO_SDK = "t1.kakaocdn.net/kakao_js_sdk";

    @Autowired MockMvc mockMvc;
    @Autowired SeoView seoView;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    private Performance performance;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();

        performance = performanceRepository.save(Performance.builder()
                .externalId("SHARE-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("공유 테스트 공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(30))
                .posterUrl("https://example.com/poster.jpg")
                .totalSeatCount(100)
                .basePrice(50_000)
                .build());
    }

    /**
     * <b>이 테스트가 잡는 결함.</b> 공유 시트를 조각으로 뺄 때 스크립트 조각 이름을
     * {@code script} 로 지었더니, {@code th:replace="~{... :: script}"} 가
     * {@code th:fragment="script"} 뿐 아니라 그 파일의 {@code <script>} <b>요소를 전부</b>
     * 같이 골라서 끼워 넣었다 — 조각 선택자는 이름만 보는 게 아니라 마크업 선택자라
     * 태그명·id·class 에도 매칭되기 때문이다.
     *
     * <p>그 결과 카카오 SDK 를 감싼 {@code th:if} 게이팅이 통째로 건너뛰어져, 키가 없는
     * 환경에서도 매 요청 카카오 CDN 스크립트를 받아왔다. <b>화면은 멀쩡해 보인다</b> —
     * 초기화는 null 키로 조용히 넘어가고 공유 옵션도 안 그려진다. 개발자도구의 네트워크 탭을
     * 열어봐야 알 수 있는 종류라, 눈으로 확인하는 것을 믿을 수 없다.
     */
    @DisplayName("카카오 JS 키가 없으면 카카오 SDK 를 아예 안 받는다")
    @Test
    void doesNotLoadKakaoSdkWithoutKey() throws Exception {
        String html = renderDetail();

        assertThat(html)
                .as("키가 없는데 SDK 를 받아오면 안 된다 (조각 선택자가 <script> 를 통째로 물어온 적이 있다)")
                .doesNotContain(KAKAO_SDK);
        assertThat(html)
                .as("옵션도 같은 조건으로 안 그려져야 한다 — 눌러도 항상 실패하는 버튼이다")
                .doesNotContain("id=\"kakaoShareOption\"");
    }

    /** 키가 없어도 공유 자체는 되어야 한다. 링크복사는 외부 의존이 없다. */
    @DisplayName("키가 없어도 공유 시트와 링크복사는 그대로 있다")
    @Test
    void keepsSheetAndCopyLinkWithoutKey() throws Exception {
        String html = renderDetail();

        assertThat(html).contains("id=\"shareSheet\"");
        assertThat(html).contains("id=\"copyLinkOption\"");
        assertThat(html).contains("id=\"shareBtn\"");
    }

    /**
     * 조각을 여러 번 끼우면 같은 id 가 문서에 두 번 생긴다. 그러면
     * {@code getElementById} 가 먼저 나온 것만 잡아, 눌러도 아무 일이 안 일어나는 시트가
     * 하나 남는다 — 위 선택자 문제로 실제로 중복 삽입이 일어났던 자리라 같이 못 박는다.
     */
    @DisplayName("공유 시트는 문서에 한 벌만 들어간다")
    @Test
    void insertsSheetOnlyOnce() throws Exception {
        String html = renderDetail();

        assertThat(countOf(html, "id=\"shareSheet\"")).isEqualTo(1);
        assertThat(countOf(html, "id=\"copyLinkOption\"")).isEqualTo(1);
    }

    /* ------------------------------------------------------------------
     *  공유 payload — 예매가 아니라 공연이 나가야 한다
     * ------------------------------------------------------------------ */

    /**
     * 결제 완료 화면의 공유는 <b>공연</b>을 가리켜야 한다. 예매번호·좌석이 섞여 나가면
     * 카톡 대화방 사람 전체가 보게 되는데 그 둘은 본인 확인 수단이다.
     */
    @DisplayName("예매 확정 공유 메타는 공연을 가리키고 예매 정보를 담지 않는다")
    @Test
    void shareMetaPointsAtPerformanceOnly() {
        String base = "https://gaekseok.example.com";
        LocalDateTime showAt = LocalDateTime.of(2026, 8, 28, 19, 0);

        SeoView.Meta meta = seoView.forPerformanceShare(performance, showAt, base);

        assertThat(meta.canonicalUrl())
                .as("링크는 공연 상세여야 한다 — 예매 상세를 공유하면 남이 열어도 볼 수 없다")
                .isEqualTo(base + "/performances/" + performance.getId());
        assertThat(meta.title()).isEqualTo("공유 테스트 공연");
        // 줄거리가 없는 공연이라 "공연장 · 관람일시" 로 대체된다.
        assertThat(meta.description()).isEqualTo("테스트홀 · 2026.8.28(금) 19:00");
        assertThat(meta.imageUrl())
                .as("카톡 서버가 바깥에서 가져가므로 절대주소여야 한다")
                .startsWith(base + "/img/poster?u=");
        assertThat(meta.jsonLd())
                .as("로그인해야 보이는 화면이라 구조화 데이터를 만들 이유가 없다")
                .isNull();
    }

    private String renderDetail() throws Exception {
        return mockMvc.perform(get("/performances/{id}", performance.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
