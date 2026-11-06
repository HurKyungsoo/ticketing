package com.portfolio.ticket.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class InfoController {

    @GetMapping("/privacy")
    public String privacy() {
        return "info/privacy";
    }
}
