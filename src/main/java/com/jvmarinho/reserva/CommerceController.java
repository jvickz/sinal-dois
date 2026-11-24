package com.jvmarinho.reserva;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.jvmarinho.reserva.CommerceModels.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Loja de demonstração")
class CommerceController {
    private static final String ID = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
    private final CommerceService commerce;
    private final CommerceSession sessions;

    CommerceController(CommerceService commerce, CommerceSession sessions) {
        this.commerce = commerce;
        this.sessions = sessions;
    }

    @GetMapping("/catalog")
    @Operation(summary = "Consultar o catálogo e o saldo disponível")
    List<Product> catalog() {
        return commerce.catalog();
    }

    @GetMapping("/session")
    @Operation(summary = "Iniciar sessão e obter X-CSRF-Token para mutações")
    ResponseEntity<CommerceSession.Info> session(HttpServletRequest request) {
        return privateResponse(sessions.initialize(request));
    }

    @PostMapping("/operator/session")
    @Operation(summary = "Entrar no painel")
    ResponseEntity<CommerceSession.Info> login(@Valid @RequestBody Credentials credentials, HttpServletRequest request) {
        return privateResponse(sessions.login(request, credentials.username(), credentials.password()));
    }

    @DeleteMapping("/operator/session")
    @Operation(summary = "Encerrar a sessão de operador")
    ResponseEntity<CommerceSession.Info> logout(HttpServletRequest request) {
        return privateResponse(sessions.logout(request));
    }

    @GetMapping("/orders")
    @Operation(summary = "Listar pedidos do comprador atual")
    ResponseEntity<List<Order>> orders(HttpServletRequest request) {
        return privateResponse(commerce.list(sessions.buyer(request)));
    }

    @PostMapping("/orders")
    @Operation(summary = "Reservar um carrinho e criar pedido")
    ResponseEntity<Order> create(@RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String key,
                                 @Valid @RequestBody Basket basket, HttpServletRequest request) {
        UUID buyer = sessions.buyerMutation(request);
        var items = basket.items().stream().map(line -> new RequestedItem(line.sku(), line.quantity())).toList();
        Checkout result = commerce.checkout(buyer, key, items);
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/orders/" + result.order().id()))
                    .cacheControl(CacheControl.noStore()).body(result.order());
        }
        return privateResponse(result.order());
    }

    @GetMapping("/orders/{id}")
    @Operation(summary = "Consultar o pedido da sessão de comprador")
    ResponseEntity<Order> order(@PathVariable @Pattern(regexp = ID) String id, HttpServletRequest request) {
        return privateResponse(commerce.get(sessions.buyer(request), UUID.fromString(id)));
    }

    @PostMapping("/orders/{id}/cancel")
    @Operation(summary = "Cancelar o checkout e devolver os itens reservados")
    ResponseEntity<Order> cancel(@PathVariable @Pattern(regexp = ID) String id, HttpServletRequest request) {
        return privateResponse(commerce.cancel(sessions.buyerMutation(request), UUID.fromString(id)));
    }

    @PostMapping("/orders/{id}/payment-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Registrar evento de pagamento simulado para processamento assíncrono")
    PaymentRequest payment(@PathVariable @Pattern(regexp = ID) String id, @Valid @RequestBody PaymentRequest event,
                           HttpServletRequest request) {
        commerce.queuePayment(sessions.buyerMutation(request), UUID.fromString(id),
                UUID.fromString(event.eventId()), event.outcome());
        return event;
    }

    @GetMapping("/operator/orders")
    @Operation(summary = "Consultar pedidos pela sessão de operador")
    ResponseEntity<List<Order>> operation(HttpServletRequest request) {
        sessions.requireOperator(request);
        return privateResponse(commerce.list(null));
    }

    @PostMapping("/operator/orders/{id}/pick")
    @Operation(summary = "Separar pedido pago")
    ResponseEntity<Order> pick(@PathVariable @Pattern(regexp = ID) String id, HttpServletRequest request) {
        sessions.operatorMutation(request);
        return privateResponse(commerce.pick(UUID.fromString(id)));
    }

    @PostMapping("/operator/orders/{id}/ship")
    @Operation(summary = "Expedir pedido que está em separação")
    ResponseEntity<Order> ship(@PathVariable @Pattern(regexp = ID) String id, HttpServletRequest request) {
        sessions.operatorMutation(request);
        return privateResponse(commerce.ship(UUID.fromString(id)));
    }

    private static <T> ResponseEntity<T> privateResponse(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    record Line(@NotNull @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,63}") String sku,
                @NotNull @Positive @Max(99) Integer quantity) {}
    record Basket(@NotEmpty @Size(max = 20) List<@NotNull @Valid Line> items) {}
    record PaymentRequest(@NotNull @Pattern(regexp = ID) String eventId, @NotNull Outcome outcome) {}
    record Credentials(@NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 200) String password) {}
}
