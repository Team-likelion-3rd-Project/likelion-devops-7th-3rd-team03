# DB 마이그레이션 이미지. k8s Job(infra/k8s/overlays/dev/migration-job.yaml)이 run-migration.sh를 실행한다.
# mysql 이미지에 bash와 mysql 클라이언트가 들어 있어서 그대로 쓴다.
FROM mysql:8.0

WORKDIR /migrations
COPY run-migration.sh 00-database-setup.sql V*.sql ./
# Windows에서 CRLF로 체크아웃된 상태로 빌드해도 bash가 읽을 수 있게 줄바꿈을 정리한다.
RUN sed -i 's/\r$//' run-migration.sh && chmod +x run-migration.sh

# mysql 이미지의 기본 entrypoint(서버 기동)를 쓰지 않는다.
ENTRYPOINT ["bash", "/migrations/run-migration.sh"]
