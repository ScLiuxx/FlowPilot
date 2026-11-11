"""AMQP messaging: unified envelope, consumer and producer."""

from app.messaging.events import (
    MessageEnvelope,
    MessageType,
    build_envelope,
    envelope_to_dict,
    parse_envelope,
)

__all__ = [
    "MessageEnvelope",
    "MessageType",
    "build_envelope",
    "envelope_to_dict",
    "parse_envelope",
]
