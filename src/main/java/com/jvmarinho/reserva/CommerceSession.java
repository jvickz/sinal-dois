package com.jvmarinho.reserva;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class CommerceSession {
    private static final String BUYER = "commerce.buyer";
    private static final String CSRF = "commerce.csrf";
    private static final String OPERATOR = "commerce.operator";
    private final String operatorPassword;

    CommerceSession(@Value("${commerce.operator-password}") String operatorPassword) {
        this.operatorPassword = operatorPassword;
    }

    Info initialize(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        if (session.getAttribute(BUYER) == null) {
            session.setAttribute(BUYER, UUID.randomUUID());
            session.setAttribute(CSRF, newToken());
        }
        return info(session);
    }

    UUID buyer(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(BUYER) instanceof UUID buyerId)) {
            throw new ApiProblem(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "Inicie uma nova sessão da loja.");
        }
        return buyerId;
    }

    UUID buyerMutation(HttpServletRequest request) {
        verifyCsrf(request);
        return buyer(request);
    }

    void requireOperator(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute(OPERATOR))) {
            throw new ApiProblem(HttpStatus.UNAUTHORIZED, "OPERATOR_REQUIRED", "Entre no painel de operações.");
        }
    }

    void operatorMutation(HttpServletRequest request) {
        requireOperator(request);
        verifyCsrf(request);
    }

    Info login(HttpServletRequest request, String username, String password) {
        HttpSession session = verifyCsrf(request);
        if (operatorPassword.isBlank() || !username.equals("operador") || !same(operatorPassword, password)) {
            throw new ApiProblem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Usuário ou senha inválidos.");
        }
        request.changeSessionId();
        session.setAttribute(OPERATOR, true);
        session.setAttribute(CSRF, newToken());
        return info(session);
    }

    Info logout(HttpServletRequest request) {
        HttpSession session = verifyCsrf(request);
        request.changeSessionId();
        session.removeAttribute(OPERATOR);
        session.setAttribute(CSRF, newToken());
        return info(session);
    }

    private static HttpSession verifyCsrf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        String supplied = request.getHeader("X-CSRF-Token");
        if (session == null || !(session.getAttribute(CSRF) instanceof String token)
                || supplied == null || !same(token, supplied)) {
            throw new ApiProblem(HttpStatus.FORBIDDEN, "INVALID_CSRF", "Atualize a sessão e tente novamente.");
        }
        return session;
    }

    private static boolean same(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }

    private static String newToken() {
        return UUID.randomUUID().toString();
    }

    private static Info info(HttpSession session) {
        return new Info((String) session.getAttribute(CSRF), Boolean.TRUE.equals(session.getAttribute(OPERATOR)));
    }

    record Info(String csrfToken, boolean operator) {}
}
