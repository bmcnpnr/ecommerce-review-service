# ecommerce-review-service
I will develop an e commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t review-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag review-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-review-service:latest
docker push bmcnpnr/ecommerce-review-service:latest

## Contract tests (Pact)

- `src/test/java/.../contract/ProductServiceConsumerPactTest` — **consumer** of product-service (Feign existence check in `createReview`). Writes `target/pacts/review-service-product-service.json`, verified in product-service.
- `.../contract/ReviewServiceProviderPactTest` — **provider** for api-gateway: the `X-Username` / `X-User-Id` / `X-User-Role` headers the gateway injects on `POST /api/v1/reviews` and `DELETE /api/v1/reviews/{id}`. Runs the real application on H2 (`src/test/resources/application-test.yaml`) with product-service mocked.

Run them alone with `mvn test -Dtest='*PactTest'`; they are ordinary Surefire tests, so `mvn verify` and CI run them too. Regenerate and redistribute pacts across repositories with `ecommerce-platform/sync-pacts.sh` (see its README, "Contract tests").
