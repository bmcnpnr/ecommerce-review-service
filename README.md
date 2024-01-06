# ecommerce-review-service
I will develop an e commerce website backend (project is suggested by ChatGPT) to develop my azure, microservices and kubernetes experience
docker build -t review-service:0.0.1-SNAPSHOT -f docker/Dockerfile .
docker tag review-service:0.0.1-SNAPSHOT bmcnpnr/ecommerce-review-service:latest
docker push bmcnpnr/ecommerce-review-service:latest
