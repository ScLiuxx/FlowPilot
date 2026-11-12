#!/usr/bin/env python3
"""End-to-end test of the resume recovery scanner.

The scenario is the one the code review described:

* the worker that continues a paused thread loses its connection to the
  broker, but the process keeps running and keeps renewing its lease,
* a duplicate delivery finds the live lease, waits for it and gives up, and
  the consumer acknowledges that message,
* the worker dies afterwards.

Nothing is left in the broker at that moment: the message was acknowledged,
so no redelivery can trigger the takeover. The resume would stay stuck
forever — without the recovery scanner, which walks the ledger on its own
and re-publishes the command of the request whose lease expired.

The test drives the real agent service over RabbitMQ and PostgreSQL:

* the reviewing agent interrupts itself,
* the state of a dead worker is written to the ledger,
* **no** resume command is published — the broker has nothing left,
* the scanner finds the request and publishes the command again,
* a worker picks the command up and continues the thread.
"""

import asyncio
import json
import os
import signal
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import aio_pika
import psycopg

RABBITMQ_URL = os.environ.get(
    "RABBITMQ_URL", "amqp://flowpilot:flowpilot@127.0.0.1:5672/"
)
EXCHANGE = "flowpilot.events"
DSN = os.environ.get(
    "POSTGRES_DSN", "postgresql://flowpilot:flowpilot@127.0.0.1:5432/flowpilot_agent"
)
START_SCRIPT = Path("/workspace/scripts/start-agent-service.sh")

INVOCATION_ID = int(time.time() * 1000) % 900_000_000 + 500_000_000
THREAD_ID = f"flowpilot-{INVOCATION_ID}"
RESUME_REQUEST_ID = f"resume-{uuid.uuid4().hex[:12]}"


def log(message: str) -> None:
    print(f"  {message}", flush=True)


def envelope(message_type: str, payload: dict) -> dict:
    now = datetime.now(tz=timezone.utc)
    return {
        "schemaVersion": "1.0",
        "messageId": str(uuid.uuid4()),
        "messageType": message_type,
        "correlationId": str(uuid.uuid4()),
        "workflowExecutionId": INVOCATION_ID,
        "nodeExecutionId": INVOCATION_ID + 1,
        "invocationId": INVOCATION_ID,
        "traceId": uuid.uuid4().hex,
        "timestamp": now.isoformat().replace("+00:00", "Z"),
        "producer": "flowpilot-resume-scanner-test",
        "payload": payload,
    }


async def publish(message_type: str, payload: dict) -> None:
    connection = await aio_pika.connect_robust(RABBITMQ_URL)
    try:
        channel = await connection.channel()
        exchange = await channel.declare_exchange(
            EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True
        )
        body = json.dumps(envelope(message_type, payload)).encode("utf-8")
        await exchange.publish(
            aio_pika.Message(
                body=body,
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                content_type="application/json",
            ),
            routing_key=message_type.replace("Agent", "agent.").lower(),
        )
    finally:
        await connection.close()


def resume_row() -> dict | None:
    with psycopg.connect(DSN, connect_timeout=5) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT status, lease_token, takeover_count,
                       (lease_expire_at IS NOT NULL AND lease_expire_at < now()) AS expired
                  FROM agent_resume_record
                 WHERE invocation_id = %s
                 ORDER BY id DESC
                 LIMIT 1
                """,
                (INVOCATION_ID,),
            )
            row = cur.fetchone()
    if row is None:
        return None
    return {
        "status": row[0],
        "leaseToken": row[1],
        "takeoverCount": row[2],
        "expired": row[3],
    }


def invocation_status() -> str | None:
    with psycopg.connect(DSN, connect_timeout=5) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT status FROM agent_execution_record WHERE invocation_id = %s",
                (INVOCATION_ID,),
            )
            row = cur.fetchone()
    return row[0] if row else None


def seed_crashed_claim() -> None:
    """Write the state a worker leaves behind when it dies during a resume.

    The worker claimed the resume and died before it could close the ledger:
    the row is in the ``CLAIMED`` state and its lease is expired. The routing
    of the original command is kept, so the scanner can rebuild it.
    """
    with psycopg.connect(DSN, connect_timeout=5) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO agent_resume_record
                    (invocation_id, resume_request_id, thread_id, status, payload,
                     lease_token, lease_expire_at, takeover_count)
                VALUES (%s, %s, %s, 'CLAIMED', %s::jsonb, 1, now() - interval '1 minute', 0)
                ON CONFLICT (invocation_id, resume_request_id) DO UPDATE
                   SET status = 'CLAIMED',
                       payload = EXCLUDED.payload,
                       lease_expire_at = now() - interval '1 minute'
                """,
                (
                    INVOCATION_ID,
                    RESUME_REQUEST_ID,
                    THREAD_ID,
                    json.dumps(
                        {
                            "workflowExecutionId": INVOCATION_ID,
                            "nodeExecutionId": INVOCATION_ID + 1,
                            "attemptNo": 1,
                            "traceId": uuid.uuid4().hex,
                            "approvalId": INVOCATION_ID + 2,
                            "resumeValue": {"approved": True, "comment": "ok"},
                        }
                    ),
                ),
            )
        conn.commit()


def kill_worker() -> list[int]:
    killed = []
    output = subprocess.run(
        ["ps", "-eo", "pid,args"], capture_output=True, text=True, check=False
    ).stdout
    for line in output.splitlines():
        if "uvicorn app.main:app" in line and "grep" not in line:
            pid = int(line.split()[0])
            try:
                os.kill(pid, signal.SIGKILL)
                killed.append(pid)
            except ProcessLookupError:
                pass
    return killed


def start_worker() -> None:
    env = dict(os.environ)
    env.setdefault("INTERNAL_API_TOKEN", "")
    # A short lease, so the scanner finds the stale request quickly.
    env["RESUME_LEASE_SECONDS"] = os.environ.get("RESUME_LEASE_SECONDS", "5")
    env["RESUME_LEASE_WAIT_SECONDS"] = os.environ.get("RESUME_LEASE_WAIT_SECONDS", "10")
    # The scanner walks the ledger every few seconds.
    env["RESUME_RECOVERY_SCAN_SECONDS"] = os.environ.get(
        "RESUME_RECOVERY_SCAN_SECONDS", "3"
    )
    env["RESUME_RECOVERY_LEASE_GRACE_SECONDS"] = os.environ.get(
        "RESUME_RECOVERY_LEASE_GRACE_SECONDS", "0"
    )
    subprocess.run(
        ["bash", str(START_SCRIPT)],
        check=True,
        capture_output=True,
        env=env,
    )


async def wait_for(predicate, timeout: float, interval: float = 1.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = predicate()
        if value:
            return value
        await asyncio.sleep(interval)
    return None


async def main() -> int:
    print("== start a worker with a short resume lease ==")
    kill_worker()
    await asyncio.sleep(1)
    start_worker()

    print("== the reviewing agent interrupts itself ==")
    await publish(
        "AgentExecute",
        {
            "agentType": "REPORT_REVIEWER",
            "threadId": THREAD_ID,
            "invocationId": INVOCATION_ID,
            "input": {
                "repository": "acme/widget",
                "analysis": {
                    "architectureAnalysis": "layered",
                    "maintenanceAnalysis": "active",
                    "riskAnalysis": "low",
                    "recommendation": "continue",
                    "riskLevel": "LOW",
                },
            },
            "config": {"maxIterations": 6, "maxToolCalls": 10, "timeoutSeconds": 60},
        },
    )

    interrupted = await wait_for(
        lambda: invocation_status() == "INTERRUPTED", timeout=60
    )
    if not interrupted:
        print("  FAIL  the agent did not interrupt itself", flush=True)
        return 1
    print("  PASS  the agent waits for the human decision", flush=True)

    print("== the worker dies and its message is gone ==")
    killed = kill_worker()
    if not killed:
        print("  FAIL  the agent worker is not running", flush=True)
        return 1
    print(f"  killed the worker: {killed}", flush=True)
    await asyncio.sleep(2)

    seed_crashed_claim()
    stale = resume_row()
    print(f"  the ledger holds the expired claim of the dead worker: {stale}", flush=True)
    if stale is None or stale["status"] != "CLAIMED" or not stale["expired"]:
        print("  FAIL  the crashed claim was not prepared", flush=True)
        return 1

    # The broker has nothing left: the message of the resume was acknowledged
    # before the worker died. Nothing would trigger the takeover.
    print("== the broker has no message left — only the scanner can recover ==")

    print("== the recovery scanner finds the request and publishes it again ==")
    start_worker()

    status = await wait_for(
        lambda: invocation_status()
        if invocation_status() in ("SUCCEEDED", "COMPLETED")
        else None,
        timeout=180,
    )
    if status not in ("SUCCEEDED", "COMPLETED"):
        print(f"  FAIL  the invocation did not complete: status={status}", flush=True)
        return 1
    print("  PASS  the invocation completed after the scanner recovered it", flush=True)

    final = resume_row()
    print(f"  the ledger: {final}", flush=True)
    if not final or final["takeoverCount"] < 1:
        print("  FAIL  the resume was not taken over", flush=True)
        return 1
    print(f"  PASS  the resume was taken over (takeover_count={final['takeoverCount']})", flush=True)

    print()
    print("ALL RESUME SCANNER TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
