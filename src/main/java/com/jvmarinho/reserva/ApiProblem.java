package com.jvmarinho.reserva;

import org.springframework.http.HttpStatus;

final class ApiProblem extends RuntimeException {
    static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

    private final HttpStatus status;
    private final String code;

    ApiProblem(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
