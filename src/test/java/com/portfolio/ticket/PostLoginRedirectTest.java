package com.portfolio.ticket;

import com.portfolio.ticket.domain.Member;
import com.portfolio.ticket.domain.MemberRole;
import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.repository.*;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그인 후 어디로 보내는가.
 *
 * <p><b>이 테스트가 생긴 이유.</b> 헤더의 「마이페이지」를 비로그인 상태로 누르면 로그인
 * 화면으로 가는데, 로그인해도 마이페이지가 아니라 <b>홈으로 떨어졌다</b>. 핸들러가
 * {@code returnTo} 파라미터와 OAuth 세션 값만 보고, 스프링이 저장해 둔 원래 요청
 * (saved request)은 안 봤기 때문이다. 그 링크에는 {@code returnTo} 가 없다.
 *
 * <p>링크마다 {@code returnTo} 를 손으로 붙이는 방식은 하나만 빠뜨려도 조용히 홈으로
 * 떨어진다 — 오류도 안 나고 화면도 멀쩡해서 눌러봐야만 안다. 그래서 "인증 때문에 막힌
 * 주소로 되돌아간다"를 테스트로 못 박는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostLoginRedirectTest {

    private static final String LOGIN_ID = "redirect-user";
    private static final String PASSWORD = "pw12345678";

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired NotificationRepository notificationRepository;
    @Autowired WishlistRepository wishlistRepository;
    @Autowired PerformanceRepository performanceRepository;
    @Autowired PerformanceScheduleRepository scheduleRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired SeatHoldRepository seatHoldRepository;
    @Autowired ReservationRepository reservationRepository;

    /** 헤더 링크가 실어 보낼 "보던 화면". 공연 상세는 비로그인도 볼 수 있다. */
    private Long performanceId;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        wishlistRepository.deleteAll();
        seatHoldRepository.deleteAll();
        seatRepository.deleteAll();
        reservationRepository.deleteAll();
        scheduleRepository.deleteAll();
        performanceRepository.deleteAll();
        memberRepository.deleteAll();

        memberRepository.save(Member.builder()
                .loginId(LOGIN_ID)
                .password(passwordEncoder.encode(PASSWORD))
                .nickname("리다이렉트")
                .role(MemberRole.USER)
                .createdAt(LocalDateTime.now())
                .build());

        performanceId = performanceRepository.save(Performance.builder()
                .externalId("REDIRECT-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("돌아오기 테스트 공연")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(30))
                .totalSeatCount(100)
                .basePrice(50_000)
                .build()).getId();
    }

    @DisplayName("인증이 필요해 막힌 화면으로 로그인 후 되돌아간다")
    @Test
    void returnsToBlockedPage() throws Exception {
        // 헤더의 「마이페이지」를 비로그인 상태로 누른 상황. 스프링이 원래 요청을 저장하고
        // 로그인으로 보낸다.
        MockHttpSession session = blockedAt("/mypage/reservations");

        mockMvc.perform(login().session(session))
                .andExpect(redirectedUrl("http://localhost/mypage/reservations"));
    }

    @DisplayName("찜한 공연에서 막혀도 그 화면으로 되돌아간다")
    @Test
    void returnsToOtherBlockedPage() throws Exception {
        MockHttpSession session = blockedAt("/mypage/wishlist");

        mockMvc.perform(login().session(session))
                .andExpect(redirectedUrl("http://localhost/mypage/wishlist"));
    }

    /**
     * 화면이 직접 지정한 목적지가 저장된 요청보다 우선이다. 좌석도·찜은 <b>로그인 없이도
     * 볼 수 있는</b> 화면에서 어떤 동작을 하려다 막힌 흐름이라, 그 화면으로 돌아가야 한다.
     */
    @DisplayName("returnTo 가 있으면 저장된 요청보다 먼저다")
    @Test
    void explicitReturnToWins() throws Exception {
        MockHttpSession session = blockedAt("/mypage/reservations");

        mockMvc.perform(login("returnTo", "/schedules/1/seats").session(session))
                .andExpect(redirectedUrl("/schedules/1/seats"));
    }

    @DisplayName("돌아갈 곳이 없으면 홈으로 간다")
    @Test
    void fallsBackToHome() throws Exception {
        mockMvc.perform(login()).andExpect(redirectedUrl("/"));
    }

    /**
     * 오픈 리다이렉트 방지. {@code returnTo} 는 폼 hidden input 이라 사용자가 바꿔 보낼 수 있다.
     */
    @DisplayName("바깥 주소로는 되돌려 보내지 않는다")
    @Test
    void rejectsExternalReturnTo() throws Exception {
        for (String evil : new String[]{"https://evil.example.com", "//evil.example.com", "javascript:alert(1)"}) {
            mockMvc.perform(login("returnTo", evil))
                    .andExpect(redirectedUrl("/"));
        }
    }

    /* ------------------------------------------------------------------
     *  로그인 없이도 볼 수 있는 화면에서 스스로 「로그인」을 누른 경우
     * ------------------------------------------------------------------ */

    /**
     * 공연 상세는 비로그인도 볼 수 있어서 <b>막힌 요청이 없다</b> — 서버는 그냥 로그인 화면을
     * 연 것으로 볼 뿐 보던 자리를 모른다. 그래서 헤더 링크가 직접 실어 보내야 한다
     * ({@code CurrentPathAdvice.currentPath}).
     */
    @DisplayName("공개 화면의 헤더 로그인 링크는 보던 자리를 실어 보낸다")
    @Test
    void headerLoginLinkCarriesCurrentPath() throws Exception {
        String html = mockMvc.perform(get("/performances/{id}", performanceId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/login?returnTo=/performances/" + performanceId);
        assertThat(html).contains("/signup?returnTo=/performances/" + performanceId);
    }

    /**
     * 목록의 필터·페이지까지 있어야 "보던 자리"가 된다.
     *
     * <p>주소에 쿼리를 직접 적는다 — {@code .param()} 을 쓰면 파라미터 맵에만 들어가고
     * {@code getQueryString()} 은 null 이라, 조건을 실어 보내는지 검증하지 못한 채 통과한다
     * (로그인 POST 본문에서 겪은 것과 같은 함정이다).
     */
    @DisplayName("목록은 필터·페이지 조건까지 실어 보낸다")
    @Test
    void headerLoginLinkKeepsQuery() throws Exception {
        String html = mockMvc.perform(get("/performances?genre=MUSICAL&page=1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // @{} 가 값 안의 = 와 & 를 인코딩하므로 디코딩하면 원래 조건이 나온다.
        assertThat(html).contains("/login?returnTo=/performances?genre%3DMUSICAL%26page%3D1");
    }

    /**
     * 로그인 화면 자신과 홈은 실어 보내지 않는다. 전자는 로그인하고 다시 로그인 화면으로
     * 돌아오는 막다른 길이고, 후자는 어차피 기본 도착지라 주소만 길어진다.
     */
    @DisplayName("로그인 화면과 홈에서는 returnTo 를 붙이지 않는다")
    @Test
    void doesNotCarryPathForLoginOrHome() throws Exception {
        String home = mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(home).contains("\"/login\"").doesNotContain("/login?returnTo=");

        String login = mockMvc.perform(get("/login")).andReturn().getResponse().getContentAsString();
        assertThat(login).doesNotContain("/login?returnTo=");
    }

    /** 인증이 필요한 주소로 들어가 로그인으로 튕긴 뒤, 저장된 요청이 담긴 세션을 돌려준다. */
    private MockHttpSession blockedAt(String path) throws Exception {
        MvcResult blocked = mockMvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        HttpSession session = blocked.getRequest().getSession(false);
        assertThat(session).as("저장된 요청이 담길 세션이 있어야 한다").isNotNull();
        return (MockHttpSession) session;
    }

    /**
     * 실제 로그인 폼과 같은 방식으로 보낸다 — {@code application/x-www-form-urlencoded} 본문.
     *
     * <p>{@code .param()} 을 쓰지 않는 이유: MockMvc 는 그걸 본문 인코딩과 무관하게 파라미터
     * 맵에 바로 넣어준다. 그러면 "본문으로 온 returnTo 를 읽을 수 있는가"를 검증하지 못한 채
     * 통과한다 — 실제 폼은 hidden input 이라 본문으로 온다.
     */
    private MockHttpServletRequestBuilder login(String... extraFormFields) {
        StringBuilder body = new StringBuilder()
                .append("username=").append(URLEncoder.encode(LOGIN_ID, StandardCharsets.UTF_8))
                .append("&password=").append(URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8));
        for (int i = 0; i < extraFormFields.length; i += 2) {
            body.append('&').append(extraFormFields[i]).append('=')
                .append(URLEncoder.encode(extraFormFields[i + 1], StandardCharsets.UTF_8));
        }
        return post("/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(body.toString())
                .with(csrf());
    }
}
