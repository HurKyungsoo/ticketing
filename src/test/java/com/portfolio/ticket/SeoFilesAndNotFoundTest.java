package com.portfolio.ticket;

import com.portfolio.ticket.domain.Performance;
import com.portfolio.ticket.domain.PerformanceCategory;
import com.portfolio.ticket.domain.SourceType;
import com.portfolio.ticket.repository.PerformanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeoFilesAndNotFoundTest {

    private static final String SAVED_REQUEST = "SPRING_SECURITY_SAVED_REQUEST";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    PerformanceRepository performanceRepository;

    @Test
    void 비로그인도_robots_txt_를_읽고_사이트맵_주소를_알_수_있다() throws Exception {
        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Disallow: /schedules/")))
                .andExpect(content().string(containsString("Sitemap: http://localhost/sitemap.xml")));
    }

    @Test
    void 사이트맵에는_끝나지_않은_공연만_들어간다() throws Exception {
        Performance running = performance(LocalDate.now().plusDays(10));
        Performance ended = performance(LocalDate.now().minusDays(1));

        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<loc>http://localhost/performances/" + running.getId() + "</loc>")))
                .andExpect(content().string(not(containsString("/performances/" + ended.getId() + "</loc>"))));
    }

    @Test
    void 비로그인이_없는_주소에_들어가면_404_이고_되돌아갈_주소로_남지_않는다() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/no-such-page").session(session))
                .andExpect(status().isNotFound());

        assertThat(session.getAttribute(SAVED_REQUEST)).isNull();
    }

    @Test
    void 비로그인이_인증이_필요한_주소에_들어가면_로그인으로_가고_되돌아올_주소가_남는다() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/mypage/reservations").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/login")));

        assertThat(session.getAttribute(SAVED_REQUEST)).isNotNull();
    }

    @Test
    void 관리자_화면은_비로그인에게_여전히_로그인을_요구한다() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/login")));
    }

    private Performance performance(LocalDate endDate) {
        return performanceRepository.save(Performance.builder()
                .externalId("SEO-" + System.nanoTime())
                .sourceType(SourceType.KOPIS)
                .title("사이트맵 테스트")
                .category(PerformanceCategory.MUSICAL)
                .venue("테스트홀")
                .region("서울특별시")
                .startDate(endDate.minusDays(5))
                .endDate(endDate)
                .totalSeatCount(100)
                .basePrice(10_000)
                .build());
    }
}
