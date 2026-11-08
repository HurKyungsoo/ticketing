package com.portfolio.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 정적 파일은 오래 캐시하되, 내용이 바뀌면 주소가 바뀌어야 한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaticResourceCacheTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 화면은_내용_해시가_붙은_CSS_주소를_쓰고_그_CSS_는_오래_캐시된다() throws Exception {
        String html = mockMvc.perform(get("/login")).andReturn().getResponse().getContentAsString();
        Matcher css = Pattern.compile("/css/app-[0-9a-f]{32}\\.css").matcher(html);
        assertThat(css.find()).as("버전이 붙은 CSS 주소").isTrue();

        mockMvc.perform(get(css.group()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
                .andExpect(header().string("Cache-Control", not(containsString("no-store"))));
    }

    @Test
    void CSS_안의_폰트_주소에도_버전이_붙는다() throws Exception {
        mockMvc.perform(get("/css/pretendard.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/fonts/pretendard/PretendardVariable.subset.0-")));
    }

    @Test
    void 화면_응답은_계속_캐시하지_않는다() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }
}
