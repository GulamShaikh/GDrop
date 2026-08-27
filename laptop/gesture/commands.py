from dataclasses import dataclass
from typing import Optional
import time

from laptop.gesture.swipe import SwipeEvent


@dataclass
class CommandEvent:
    name: str
    timestamp: float
    source_swipe: SwipeEvent


class GestureCommandBridge:
    """Converts SwipeEvent instances into high-level commands.

    Mappings:
      - SWIPE_RIGHT -> SEND_REQUEST
      - SWIPE_LEFT  -> CANCEL_REQUEST

    The bridge applies a short debounce (by timestamp) so a single swipe
    generates only one command even if upstream produces the same SwipeEvent
    multiple times.
    """

    def __init__(self, debounce: float = 0.6) -> None:
        self.debounce = debounce
        self._last_command_time = 0.0
        self._last_command_name: Optional[str] = None

    def process_swipe(self, swipe: SwipeEvent) -> Optional[CommandEvent]:
        if swipe is None:
            return None

        now = time.time()
        # Map swipe name to command
        mapping = {
            "SWIPE_RIGHT": "SEND_REQUEST",
            "SWIPE_LEFT": "CANCEL_REQUEST",
        }

        cmd_name = mapping.get(swipe.name)
        if not cmd_name:
            return None

        # debounce: if same command was produced recently, ignore
        if now - self._last_command_time < self.debounce and cmd_name == self._last_command_name:
            return None

        # produce command
        self._last_command_time = now
        self._last_command_name = cmd_name
        return CommandEvent(name=cmd_name, timestamp=now, source_swipe=swipe)


# convenience function
def swipe_to_command(swipe: SwipeEvent, debounce: float = 0.6) -> Optional[CommandEvent]:
    bridge = GestureCommandBridge(debounce=debounce)
    return bridge.process_swipe(swipe)


# Command sender that can call an HTTP endpoint (optional)
import json
import os
from urllib import request as urlrequest
from urllib.error import URLError, HTTPError


class CommandSender:
    """Sends CommandEvent to a configured HTTP endpoint (if set).

    The endpoint should accept JSON POST with fields: name, timestamp.
    If GDROP_COMMAND_ENDPOINT environment variable is not set, the sender is a no-op
    (it only returns True locally).
    """

    def __init__(self, endpoint: str | None = None, timeout: float = 2.0) -> None:
        # Default to posting to local laptop server gesture endpoint so the Android app
        # can poll for commands. This keeps integration local and optional.
        self.endpoint = endpoint or os.environ.get("GDROP_COMMAND_ENDPOINT") or "http://127.0.0.1:8000/gesture-command"
        self.timeout = timeout

    def send(self, cmd: CommandEvent) -> bool:
        if not self.endpoint:
            # no endpoint configured, act as local no-op successful send
            return True

        body = json.dumps({"name": cmd.name, "timestamp": cmd.timestamp}).encode("utf-8")
        req = urlrequest.Request(self.endpoint, data=body, headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urlrequest.urlopen(req, timeout=self.timeout) as resp:
                return 200 <= resp.getcode() < 300
        except (HTTPError, URLError, OSError):
            return False
