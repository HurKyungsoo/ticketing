package com.portfolio.ticket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SiteFooterTest {

    /** 하단이 결제·예매 고정 바라서 푸터를 두지 않는 화면. */
    private static final Set<String> NO_FOOTER = Set.of("seat-map.html", "payment.html");

    @Autowired
    MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/performances", "/login", "/signup", "/privacy"})
    void 비로그인_화면에_푸터가_나오고_테스트_결제_안내는_없다(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("site-footer")))
                .andExpect(content().string(not(containsString("test-payment-notice"))));
    }

    @Test
    void 회원가입_화면에서_개인정보_안내로_갈_수_있다() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(content().string(containsString("signup-privacy")))
                .andExpect(content().string(containsString("href=\"/privacy\"")));
    }

    /** 새 화면을 만들 때 빠뜨리지 않게, 헤더를 쓰는 모든 템플릿을 검사한다. */
    @Test
    void 헤더가_있는_모든_템플릿에_푸터가_있다() throws IOException {
        Resource[] templates = new PathMatchingResourcePatternResolver()
                .getResources("classpath:templates/**/*.html");
        int checked = 0;

        for (Resource template : templates) {
            String html = template.getContentAsString(StandardCharsets.UTF_8);
            if (!html.contains("fragment/header :: masthead(")) continue;
            checked++;

            String name = template.getFilename();
            if (!NO_FOOTER.contains(name)) {
                assertThat(html).as(name).contains("fragment/site :: footer");
            }
        }
        assertThat(checked).isGreaterThan(20);
    }

    @Test
    void 결제_완료_화면에_테스트_예매_안내가_있다() throws IOException {
        String html = new ClassPathResource("templates/reservation/payment-success.html")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("테스트 예매입니다");
    }
}
