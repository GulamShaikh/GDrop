from __future__ import annotations

from dataclasses import dataclass
from math import hypot
from typing import Sequence


@dataclass
class GestureResult:
    name: str
    confidence: float


class GestureClassifier:
    def classify(self, landmarks: Sequence[object]) -> GestureResult:
        if len(landmarks) != 21:
            return GestureResult("UNKNOWN", 0.0)

        def distance(a, b):
            return hypot(a.x - b.x, a.y - b.y)

        # Pinch detection
        pinch_ratio = distance(landmarks[4], landmarks[8]) / max(
            distance(landmarks[0], landmarks[8]), 1e-6
        )

        if pinch_ratio < 0.30:
            return GestureResult("PINCH", 0.95)

        # Finger states
        index_open = landmarks[8].y < landmarks[6].y
        middle_open = landmarks[12].y < landmarks[10].y
        ring_open = landmarks[16].y < landmarks[14].y
        pinky_open = landmarks[20].y < landmarks[18].y

        open_count = sum(
            [index_open, middle_open, ring_open, pinky_open]
        )

        if open_count >= 4:
            return GestureResult("OPEN_PALM", 0.95)

        if open_count == 0:
            return GestureResult("FIST", 0.90)

        return GestureResult("UNKNOWN", 0.50)