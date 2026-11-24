package com.jvmarinho.reserva;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.jvmarinho.reserva.ReservationStatus.CANCELLED;
import static com.jvmarinho.reserva.ReservationStatus.CONFIRMED;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class ReservationController {
    private static final String SKU_FORMAT = "[A-Z0-9][A-Z0-9_-]{0,63}";
    private static final String UUID_FORMAT = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
    private final ReservationService service;

    ReservationController(ReservationService service) {
        this.service = service;
    }

    @Tag(name = "Estoque")
    @Operation(summary = "Consultar saldo disponível")
    @ApiResponse(responseCode = "200", description = "Saldo disponível", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "SKU inexistente", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/inventory/{sku}")
    Stock stock(@Parameter(example = "IPHONE-15-128") @PathVariable @Pattern(regexp = SKU_FORMAT) String sku) {
        return service.stock(sku);
    }

    @Tag(name = "Reservas")
    @Operation(summary = "Reservar unidades de um SKU", description = """
            Use uma chave de idempotência por compra. Repita a mesma chave e o mesmo corpo para recuperar uma reserva existente.
            """)
    @ApiResponse(responseCode = "201", description = "Reserva criada", useReturnTypeSchema = true,
            headers = @Header(name = "Location", description = "Endereço da reserva"))
    @ApiResponse(responseCode = "200", description = "Reenvio idempotente", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Chave ou corpo inválido", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "SKU inexistente", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Saldo insuficiente ou chave já vinculada a outro payload",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/reservations", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Reservation> create(
            @Parameter(example = "checkout-001") @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String key,
            @Valid @RequestBody CreateReservation body) {
        var result = service.create(key, body.sku(), body.quantity());
        Reservation reservation = result.reservation();
        if (result.created()) {
            return ResponseEntity.created(URI.create("/api/v1/reservations/" + reservation.id())).body(reservation);
        }
        return ResponseEntity.ok(reservation);
    }

    @Tag(name = "Reservas")
    @Operation(summary = "Consultar a reserva")
    @ApiResponse(responseCode = "200", description = "Estado persistido da reserva", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "404", description = "Reserva inexistente", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/reservations/{id}")
    Reservation get(@Parameter(schema = @Schema(type = "string", format = "uuid"))
                    @PathVariable @Pattern(regexp = UUID_FORMAT) String id) {
        return service.get(UUID.fromString(id));
    }

    @Tag(name = "Reservas")
    @Operation(summary = "Confirmar a compra e manter o débito do estoque")
    @ApiResponse(responseCode = "200", description = "Reserva confirmada", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "O estado da reserva impede a confirmação", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/reservations/{id}/confirm")
    Reservation confirm(@Parameter(schema = @Schema(type = "string", format = "uuid"))
                        @PathVariable @Pattern(regexp = UUID_FORMAT) String id) {
        return service.changeStatus(UUID.fromString(id), CONFIRMED);
    }

    @Tag(name = "Reservas")
    @Operation(summary = "Cancelar a reserva e devolver unidades ao estoque")
    @ApiResponse(responseCode = "200", description = "Reserva cancelada", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "409", description = "O estado da reserva impede o cancelamento", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/reservations/{id}/cancel")
    Reservation cancel(@Parameter(schema = @Schema(type = "string", format = "uuid"))
                       @PathVariable @Pattern(regexp = UUID_FORMAT) String id) {
        return service.changeStatus(UUID.fromString(id), CANCELLED);
    }

    record CreateReservation(
            @Schema(example = "IPHONE-15-128") @NotNull @Pattern(regexp = SKU_FORMAT) String sku,
            @Schema(example = "1") @NotNull @Positive Integer quantity) {}
}
