import time
from typing import Optional, Tuple
from pathlib import Path

import cv2
import numpy as np

# Use the MediaPipe Tasks API (v1.0+)
from mediapipe.tasks.python.vision.hand_landmarker import (
    HandLandmarker,
    HandLandmarkerOptions,
)
from mediapipe.tasks.python.core import base_options as base_options_lib
from mediapipe.tasks.python.vision.core import image as mp_image
from mediapipe.tasks.python.vision.core import vision_task_running_mode as running_mode_lib
from mediapipe.tasks.python.vision import hand_landmarker as hl_module


class HandDetector:
    """Hand detector using MediaPipe Tasks HandLandmarker (mediapipe 1.0+).

    This implementation uses the HandLandmarker in VIDEO mode and calls
    detect_for_video for each frame. It requires a local MediaPipe task
    model asset (hand_landmarker.task). By default the detector looks for the
    model at: laptop/gesture/models/hand_landmarker.task

    To download the model bundle, get the `hand_landmarker.task` file from the
    MediaPipe model bundles. Example source:
    https://github.com/google/mediapipe/tree/master/mediapipe/tasks/portable

    Place the model at: laptop/gesture/models/hand_landmarker.task
    """

    def __init__(
        self,
        model_path: Optional[str] = None,
        max_hands: int = 1,
        detection_confidence: float = 0.5,
        tracking_confidence: float = 0.5,
    ) -> None:
        self.model_path = (
            Path(model_path) if model_path else Path(__file__).resolve().parents[1] / "models" / "hand_landmarker.task"
        )
        if not self.model_path.exists():
            raise FileNotFoundError(
                f"HandLandmarker model not found at {self.model_path}.\n"
                "Download the MediaPipe hand_landmarker.task bundle and place it at the path above."
            )

        base_options = base_options_lib.BaseOptions(model_asset_path=str(self.model_path))
        options = hl_module.HandLandmarkerOptions(
            base_options=base_options,
            running_mode=running_mode_lib.VisionTaskRunningMode.VIDEO,
            num_hands=max_hands,
            min_hand_detection_confidence=detection_confidence,
            min_tracking_confidence=tracking_confidence,
        )

        # Create the hand landmarker
        self.landmarker = HandLandmarker.create_from_options(options)
        self._last_timestamp_ms = 0

    def process_frame(self, frame: np.ndarray) -> Tuple[np.ndarray, int, object]:
        """Process a BGR OpenCV frame and return annotated frame, hand count, and raw result.

        Returns:
          (annotated_frame, hand_count, result)
        """
        # Convert BGR to RGB for MediaPipe
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        # Create MediaPipe Image from ndarray (expects RGB uint8)
        mp_img = mp_image.Image(mp_image.ImageFormat.SRGB, rgb)

        timestamp_ms = int(time.time() * 1000)
        # ensure monotonic timestamps
        if timestamp_ms <= self._last_timestamp_ms:
            timestamp_ms = self._last_timestamp_ms + 1
        self._last_timestamp_ms = timestamp_ms

        # Run detection for video frame
        result = self.landmarker.detect_for_video(mp_img, timestamp_ms)

        hand_count = 0
        height, width, _ = frame.shape

        if result.hand_landmarks:
            hand_count = len(result.hand_landmarks)
            for hand_landmarks in result.hand_landmarks:
                # Draw landmarks
                for lm in hand_landmarks:
                    x_px = int(lm.x * width)
                    y_px = int(lm.y * height)
                    cv2.circle(frame, (x_px, y_px), 3, (0, 255, 0), -1)

                # Draw connections
                for conn in hl_module.HandLandmarksConnections.HAND_CONNECTIONS:
                    start = hand_landmarks[conn.start]
                    end = hand_landmarks[conn.end]
                    sx = int(start.x * width)
                    sy = int(start.y * height)
                    ex = int(end.x * width)
                    ey = int(end.y * height)
                    cv2.line(frame, (sx, sy), (ex, ey), (0, 200, 50), 2)

        return frame, hand_count, result

    def close(self) -> None:
        try:
            self.landmarker.close()
        except Exception:
            pass
