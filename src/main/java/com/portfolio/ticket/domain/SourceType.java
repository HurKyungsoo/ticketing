package com.portfolio.ticket.domain;

/** 공연이 어느 공공데이터 소스에서 왔는지. externalId 접두어(KOPIS-/STD-/CIA-/SEED-)와 1:1 대응. */
public enum SourceType {
    KOPIS, STANDARD, CULTURE, SEED
}
