# ecommerce-review-service

Product reviews and ratings. A logged-in user can write one review per product,
with a rating from 1 to 5. The service checks over Feign that the product
exists. Reviews start `PENDING` and become visible on the product page once
moderated to `APPROVED`. Users vote reviews helpful or unhelpful, one vote each.
The product page shows the approved reviews and a rating summary, both public.

| | |
|---|---|
| Port | 8087 |
| Gateway routes | `/api/v1/reviews/**` (`GET /product/**` is public) |
| Data store | PostgreSQL `reviewdb` (`reviews`, `review_votes`, Flyway) |
| Calls | product-service (Feign: does the product exist?), with connect-failure retries |
| Identity | the author is `X-Username`. Only the author may edit; the author or `X-User-Role: ADMIN` may delete. |
| Docs | [Deep dive](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/services/review-service.md) · [API reference](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/api/review-service.md) · Swagger UI at http://localhost:8080/swagger-ui.html (`review-service`) · [All docs](https://github.com/bmcnpnr/ecommerce-platform/blob/develop/docs/README.md) |

With the repositories checked out side by side, the same docs are in
`../ecommerce-platform/docs/`.

> **Known gaps** (see the deep dive): moderation has no role check; the
> configured circuit breaker and fallback are inactive; a missing product and a
> product-service outage both answer 500.

## Build and run

```bash
mvn -B verify                       # unit and contract tests (H2)
mvn -B clean package -DskipTests    # the jar the Docker image copies
cd ../ecommerce-platform && docker compose up -d --build review-service
```

## Contract tests (Pact)

- `contract/ProductServiceConsumerPactTest`: **consumer** of product-service (the Feign existence check in `createReview`). Writes `target/pacts/review-service-product-service.json`, verified in product-service.
- `contract/ReviewServiceProviderPactTest`: **provider** for api-gateway. Covers the `X-Username` / `X-User-Id` / `X-User-Role` headers the gateway injects on `POST /api/v1/reviews` and `DELETE /api/v1/reviews/{id}`, run against the real application on H2 (`src/test/resources/application-test.yaml`) with product-service mocked.

Run them alone with `mvn test -Dtest='*PactTest'`. Redistribute pacts with
`ecommerce-platform/sync-pacts.sh`.
