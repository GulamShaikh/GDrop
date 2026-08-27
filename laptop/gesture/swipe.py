from collections import deque
from dataclasses import dataclass
from typing import Optional, Deque, Tuple
import time


@dataclass
class SwipeEvent:
    name: str
    timestamp: float
    distance: float
    duration: float


class SwipeDetector:
    """Detects horizontal swipe gestures from a stream of (x,y,t) positions.

    Positions are expected in normalized image coordinates (x in [0,1]).

    Detection policy (tunable):
    - Keep a short history of recent samples (sample_window seconds).
    - Find earliest sample within the window and compare to latest to estimate
      displacement and duration.
    - Trigger a swipe if absolute horizontal displacement >= min_distance,
      duration between min_duration and max_duration, and horizontal motion
      dominates vertical motion.
    - After a swipe is detected, a cooldown prevents repeated triggers.
    """

    def __init__(
        self,
        min_distance: float = 0.25,
        min_duration: float = 0.05,
        max_duration: float = 0.7,
        sample_window: float = 1.0,
        cooldown: float = 0.6,
        horizontal_vs_vertical_ratio: float = 1.5,
    ) -> None:
        self.min_distance = min_distance
        self.min_duration = min_duration
        self.max_duration = max_duration
        self.sample_window = sample_window
        self.cooldown = cooldown
        self.hv_ratio = horizontal_vs_vertical_ratio

        self._history: Deque[Tuple[float, float, float]] = deque()
        self._last_swipe_time: float = 0.0

    def reset(self) -> None:
        self._history.clear()
        self._last_swipe_time = 0.0

    def _prune(self, now: float) -> None:
        cutoff = now - self.sample_window
        while self._history and self._history[0][0] < cutoff:
            self._history.popleft()

    def update(self, x: float, y: float, timestamp: Optional[float] = None) -> Optional[SwipeEvent]:
        """Feed a new palm/wrist position sample and return a SwipeEvent on detection.

        x,y: normalized coordinates (0..1)
        timestamp: seconds (time.time()). If None use time.time().
        """
        now = timestamp if timestamp is not None else time.time()

        # Append sample and prune old ones
        self._history.append((now, float(x), float(y)))
        self._prune(now)

        # Respect cooldown
        if now - self._last_swipe_time < self.cooldown:
            return None

        if not self._history:
            return None

        t0, x0, y0 = self._history[0]
        t1, x1, y1 = self._history[-1]
        dt = t1 - t0
        if dt <= 0:
            return None

        dx = x1 - x0
        dy = y1 - y0
        abs_dx = abs(dx)
        abs_dy = abs(dy)

        # Duration requirement
        if dt < self.min_duration:
            return None
        if dt > self.max_duration:
            return None

        # Horizontal dominance
        if abs_dx < self.min_distance:
            return None
        if abs_dx < self.hv_ratio * abs_dy:
            return None

        # Determine direction
        name = "SWIPE_RIGHT" if dx > 0 else "SWIPE_LEFT"

        # Register swipe and apply cooldown
        self._last_swipe_time = now
        # Clear history to avoid repeated detections from same motion
        self._history.clear()

        return SwipeEvent(name=name, timestamp=now, distance=abs_dx, duration=dt)
