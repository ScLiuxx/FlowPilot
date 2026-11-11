"""Tool layer of the agent runtime.

Every tool in this package obeys the security rules of the protocol design
document (section 12.3):

* explicit allow list of the operations,
* no arbitrary URL fetching, no shell execution, no local file access,
* repository content is treated as untrusted data and never as instructions.
"""

from app.tools.untrusted import (
    UNTRUSTED_BLOCK_END,
    UNTRUSTED_BLOCK_START,
    sanitize_untrusted,
    untrusted_block,
)

__all__ = [
    "sanitize_untrusted",
    "untrusted_block",
    "UNTRUSTED_BLOCK_START",
    "UNTRUSTED_BLOCK_END",
]
