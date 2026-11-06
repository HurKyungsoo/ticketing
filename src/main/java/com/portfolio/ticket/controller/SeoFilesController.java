package com.portfolio.ticket.controller;

import com.portfolio.ticket.service.SeoFilesService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;

@RestController
@RequiredArgsConstructor
public class SeoFilesController {

    private static final CacheControl ONE_HOUR = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    private final SeoFilesService seoFilesService;

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .cacheControl(ONE_HOUR)
                .body(seoFilesService.robotsTxt(baseUrl()));
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .cacheControl(ONE_HOUR)
                .body(seoFilesService.sitemapXml(baseUrl()));
    }

    private String baseUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }
}
