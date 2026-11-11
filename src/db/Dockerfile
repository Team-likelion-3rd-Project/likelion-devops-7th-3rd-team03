# DB 마이그레이션 이미지. k8s Job(infra/k8s/overlays/dev/migration-job.yaml)이 run-migration.sh를 실행한다.
# mysql 이미지에 bash와 mysql 클라이언트가 들어 있어서 그대로 쓴다.
FROM mysql:8.0

WORKDIR /migrations
COPY run-migration.sh 00-database-setup.sql V*.sql ./
# 마이그레이션은 bash와 mysql 클라이언트만 쓴다.
# 서버 기동용 도구(gosu)와 MySQL Shell은 쓰지 않아서 지운다. 둘에 포함된 취약점(Go stdlib, Python certifi)도 같이 사라진다.
RUN microdnf remove -y mysql-shell \
 && microdnf clean all \
 && rm -f /usr/local/bin/gosu

# mysql 이미지의 기본 entrypoint(서버 기동)를 쓰지 않는다.
ENTRYPOINT ["bash", "/migrations/run-migration.sh"]
