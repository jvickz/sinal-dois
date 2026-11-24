package com.jvmarinho.reserva;

import java.time.Clock;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@OpenAPIDefinition(info = @Info(title = "Sinal Dois", version = "1.0",
        description = "Loja de eletrônicos com catálogo, reservas de estoque, pagamento simulado e expedição de pedidos."))
@SpringBootApplication
@EnableScheduling
public class StockReservationApplication {
    public static void main(String[] args) {
        SpringApplication.run(StockReservationApplication.class, args);
    }

    @Bean
    Clock reservationClock() {
        return Clock.systemUTC();
    }

    @Bean
    JsonMapperBuilderCustomizer strictJsonStrings() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
