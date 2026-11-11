#!/usr/bin/env bash
# Start local dev infrastructure (MySQL / Redis / PostgreSQL / RabbitMQ) detached from the shell session.
set -u

LOG_DIR=/tmp/flowpilot-logs
mkdir -p "$LOG_DIR" /var/run/mysqld /var/log/mysql
chown -R mysql:mysql /var/run/mysqld /var/log/mysql

start_mysql() {
  if mysqladmin --protocol=socket status >/dev/null 2>&1; then
    echo "mysql: already running"; return
  fi
  setsid nohup mysqld_safe --user=mysql --socket=/var/run/mysqld/mysqld.sock \
    --pid-file=/var/run/mysqld/mysqld.pid > "$LOG_DIR/mysqld.log" 2>&1 < /dev/null &
  for i in $(seq 1 40); do
    mysqladmin --protocol=socket status >/dev/null 2>&1 && break
    sleep 1
  done
  mysqladmin --protocol=socket status >/dev/null 2>&1 && echo "mysql: started" || echo "mysql: FAILED"
}

start_redis() {
  if redis-cli ping >/dev/null 2>&1; then echo "redis: already running"; return; fi
  setsid nohup redis-server --daemonize no --port 6379 --bind 127.0.0.1 \
    --dir /tmp --save "" > "$LOG_DIR/redis.log" 2>&1 < /dev/null &
  for i in $(seq 1 20); do redis-cli ping >/dev/null 2>&1 && break; sleep 1; done
  redis-cli ping >/dev/null 2>&1 && echo "redis: started" || echo "redis: FAILED"
}

start_postgres() {
  if su postgres -c "pg_isready -q" >/dev/null 2>&1; then echo "postgres: already running"; return; fi
  pg_ctlcluster 16 main start >/dev/null 2>&1
  for i in $(seq 1 30); do su postgres -c "pg_isready -q" >/dev/null 2>&1 && break; sleep 1; done
  su postgres -c "pg_isready -q" >/dev/null 2>&1 && echo "postgres: started" || echo "postgres: FAILED"
}

start_rabbitmq() {
  if rabbitmqctl -q status >/dev/null 2>&1; then echo "rabbitmq: already running"; return; fi
  setsid nohup /usr/sbin/rabbitmq-server > "$LOG_DIR/rabbit.log" 2>&1 < /dev/null &
  for i in $(seq 1 60); do rabbitmqctl -q status >/dev/null 2>&1 && break; sleep 1; done
  rabbitmqctl -q status >/dev/null 2>&1 && echo "rabbitmq: started" || echo "rabbitmq: FAILED"
}

bootstrap_databases() {
  mysql --protocol=socket -uroot <<'SQL'
CREATE DATABASE IF NOT EXISTS flowpilot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS flowpilot_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'flowpilot'@'%' IDENTIFIED BY 'flowpilot';
CREATE USER IF NOT EXISTS 'flowpilot'@'localhost' IDENTIFIED BY 'flowpilot';
GRANT ALL PRIVILEGES ON flowpilot.* TO 'flowpilot'@'%';
GRANT ALL PRIVILEGES ON flowpilot_test.* TO 'flowpilot'@'%';
GRANT ALL PRIVILEGES ON flowpilot.* TO 'flowpilot'@'localhost';
GRANT ALL PRIVILEGES ON flowpilot_test.* TO 'flowpilot'@'localhost';
FLUSH PRIVILEGES;
SQL
  su postgres -c "psql -tAc \"SELECT 1 FROM pg_roles WHERE rolname='flowpilot'\"" | grep -q 1 \
    || su postgres -c "psql -c \"CREATE USER flowpilot WITH PASSWORD 'flowpilot';\""
  su postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='flowpilot_agent'\"" | grep -q 1 \
    || su postgres -c "psql -c 'CREATE DATABASE flowpilot_agent OWNER flowpilot;'"
  su postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='flowpilot_agent_test'\"" | grep -q 1 \
    || su postgres -c "psql -c 'CREATE DATABASE flowpilot_agent_test OWNER flowpilot;'"
  rabbitmqctl -q list_users 2>/dev/null | grep -q '^flowpilot' \
    || rabbitmqctl add_user flowpilot flowpilot >/dev/null 2>&1
  rabbitmqctl -q set_user_tags flowpilot administrator >/dev/null 2>&1
  rabbitmqctl -q set_permissions -p / flowpilot ".*" ".*" ".*" >/dev/null 2>&1
  echo "databases: bootstrapped"
}

case "${1:-all}" in
  start)
    start_mysql; start_redis; start_postgres; start_rabbitmq; bootstrap_databases ;;
  status)
    mysqladmin --protocol=socket status 2>&1 | head -1
    redis-cli ping 2>&1
    su postgres -c "pg_isready" 2>&1
    rabbitmqctl -q status >/dev/null 2>&1 && echo "RabbitMQ running" || echo "RabbitMQ down"
    ;;
  *) echo "usage: $0 {start|status}" ;;
esac
