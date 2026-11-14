"""The classification of the failures that are worth another attempt.

A database that restarts or a connection that is lost says nothing about
the work itself. Such a failure must not dead letter the command, because
the agent would then never report its result and the workflow would wait
for it for ever.
"""

from __future__ import annotations

import psycopg
import pytest
from aio_pika import exceptions as aio_pika_exceptions

from app.messaging.consumer import _is_retryable


@pytest.mark.parametrize(
    "error",
    [
        aio_pika_exceptions.AMQPConnectionError("broker down"),
        ConnectionResetError("connection reset"),
        TimeoutError("timeout"),
        OSError("network unreachable"),
        psycopg.OperationalError("the database is restarting"),
        psycopg.InterfaceError("the connection is closed"),
    ],
)
def test_an_infrastructure_failure_is_retried(error):
    assert _is_retryable(error) is True


@pytest.mark.parametrize(
    "error",
    [
        ValueError("the payload is wrong"),
        RuntimeError("the graph failed"),
        KeyError("missing field"),
    ],
)
def test_a_business_failure_is_not_retried(error):
    assert _is_retryable(error) is False
