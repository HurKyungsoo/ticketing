package com.portfolio.ticket.domain;

public enum SeatGrade {
    VIP(1.5), R(1.2), S(1.0), A(0.8);

    private final double priceRate;
    SeatGrade(double priceRate) { this.priceRate = priceRate; }
    public int applyTo(int basePrice) { return (int) (basePrice * priceRate); }
}
