# Reserva de Estoque

API de reservas temporárias em Java e PostgreSQL. Duas compras podem disputar a última unidade. O PostgreSQL decide qual reserva é criada, e a API devolve um conflito à outra compra. Reenviar o mesmo pedido com a mesma chave recupera a reserva existente, com um único débito de estoque.

## Executar a demonstração

Com Docker e Docker Compose em execução, rode na pasta do projeto.

```sh
docker compose up --build -d
```

Abra [Swagger UI](http://127.0.0.1:8090/swagger-ui.html). O Swagger permite executar as requisições e inspecionar os status e corpos das respostas. A API está acessível na interface local. O banco usa um volume Docker e fica na rede interna do Compose.

O perfil `demo` inicializa `CAFE-250G` com uma unidade e `FILTRO-01` com dez. A porta do host pode ser alterada com `RESERVA_PORT`. O prazo padrão é de dois minutos, configurável por `RESERVATION_TTL`, por exemplo `30s` para uma demonstração mais curta.

## Roteiro de dois minutos

1. Em `GET /api/v1/inventory/{sku}`, consulte `CAFE-250G` e confira o saldo inicial.
2. Em `POST /api/v1/reservations`, use uma chave nova, como `checkout-001`, no cabeçalho `Idempotency-Key`. Envie `{"sku":"CAFE-250G","quantity":1}`. A resposta `201` informa o ID, o estado `HELD` e o prazo em UTC.
3. Repita a chamada com a mesma chave e corpo. A resposta `200` contém o mesmo ID. Troque apenas a chave e envie novamente. A resposta `409` apresenta `INSUFFICIENT_STOCK`.
4. Cancele a reserva pelo ID em `POST /api/v1/reservations/{id}/cancel` e consulte o estoque. A unidade volta uma vez, mesmo que você repita o cancelamento. Crie outra reserva com uma chave nova e deixe o prazo vencer para observar `EXPIRED`.
5. Abra [ReservationApiIT](src/test/java/com/jvmarinho/reserva/ReservationApiIT.java) para mostrar como o teste dispara as duas compras simultaneamente e verifica que só uma reserva foi gravada.

O fluxo de confirmação muda a reserva para `CONFIRMED` e mantém o débito. As reservas ficam registradas no banco entre reinícios. `docker compose down` encerra a demonstração preservando o volume.

## Decisões técnicas

| Decisão | Comportamento observável |
| --- | --- |
| Débito condicional no PostgreSQL | A atualização só é aplicada se houver unidades suficientes. A restrição do banco protege o saldo contra valores negativos. |
| Chave de idempotência única | Retentativas simultâneas recuperam a mesma reserva. O mesmo identificador com outro SKU ou quantidade retorna `409`. Uma tentativa recusada pode ser repetida depois de repor o saldo. |
| Bloqueio da linha da reserva | Confirmação, cancelamento e expiração verificam o estado e o prazo após adquirir o bloqueio. A devolução do saldo ocorre na mesma transação da mudança de estado. |
| Worker em lotes | O worker consulta reservas vencidas persistidas e retoma a expiração após reiniciar. A consulta HTTP mostra o estado armazenado e o prazo em UTC. |

O [documento OpenAPI](http://127.0.0.1:8090/v3/api-docs) descreve o contrato HTTP. As falhas de validação e de regra de negócio usam `application/problem+json` e um campo `code` para distinguir as causas. Para observar a aplicação, consulte [saúde](http://127.0.0.1:8090/actuator/health) e [contadores de reservas](http://127.0.0.1:8090/actuator/metrics/inventory.reservations.events) no Actuator. Os contadores representam o processo atual.

## Testar e reproduzir

Com Java 21, Maven e Docker em execução, rode:

```sh
mvn -B -ntp verify
```

O Surefire verifica as regras de prazo com um relógio controlado. O Failsafe executa testes HTTP contra PostgreSQL 18 isolado por Testcontainers. Os testes cobrem a concorrência pela última unidade, o reenvio, a disputa entre confirmação e cancelamento e a devolução por expiração. O workflow de GitHub Actions executa o mesmo comando.

### Linha para o currículo

> Desenvolvi uma API Java/Spring Boot de reservas de estoque com Swagger e PostgreSQL. Usei transações para evitar reservas duplicadas ou acima do saldo e cobri concorrência e expiração com testes de integração em Testcontainers.
