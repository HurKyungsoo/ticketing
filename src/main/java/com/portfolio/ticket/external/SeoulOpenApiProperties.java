package com.portfolio.ticket.external;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "seoul-open-api")
public class SeoulOpenApiProperties {
    private String serviceKey;
    private String baseUrl;
    private int syncPageSize = 100;
    private int syncMaxPages = 5;
}
