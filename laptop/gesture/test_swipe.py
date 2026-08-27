import sys
import time
from pathlib import Path

if __package__ in (None, ""):
    repo_root = Path(__file__).resolve().parents[2]
    if str(repo_root) not in sys.path:
        sys.path.insert(0, str(repo_root))

from laptop.gesture.classifier import GestureClassifier
from laptop.gesture.swipe import SwipeDetector

import cv2


def main() -> int:
    # Look for model in gesture/models first (common), then laptop/models as fallback
    gesture_model = Path(__file__).resolve().parents[0] / "models" / "hand_landmarker.task"
    laptop_model = Path(__file__).resolve().parents[1] / "models" / "hand_landmarker.task"
    if gesture_model.exists():
        model_path = gesture_model
    else:
        model_path = laptop_model

    from laptop.gesture.detector import HandDetector

    print(f"Using hand landmarker model at: {model_path}")
    detector = HandDetector(model_path=str(model_path), max_hands=1)
    classifier = GestureClassifier()
    swipe = SwipeDetector(
        min_distance=0.12,
        min_duration=0.05,
        max_duration=1.0,
        sample_window=1.0,
        cooldown=0.8,
        horizontal_vs_vertical_ratio=1.2,
    )

    camera = cv2.VideoCapture(0)

    if not camera.isOpened():
        print("Unable to open webcam. Please connect a camera and try again.")
        return 1

    start_time = time.time()
    frame_count = 0
    last_swipe: str | None = None
    last_swipe_time = 0.0

    try:
        while True:
            success, frame = camera.read()
            if not success:
                print("Failed to read frame from webcam.")
                break

            processed_frame, hand_count, result = detector.process_frame(frame)

            gesture_text = "Gesture: UNKNOWN"
            confidence = 0.0

            # Update swipe detector with wrist/palm center x
            palm_x = None
            palm_y = None

            if result and getattr(result, "hand_landmarks", None):
                hand_landmarks = result.hand_landmarks[0]
                # Use wrist (landmark 0) if available
                try:
                    lm0 = hand_landmarks[0]
                    palm_x = lm0.x
                    palm_y = lm0.y
                except Exception:
                    # Fallback to average of all landmarks
                    xs = [lm.x for lm in hand_landmarks]
                    ys = [lm.y for lm in hand_landmarks]
                    if xs:
                        palm_x = sum(xs) / len(xs)
                        palm_y = sum(ys) / len(ys)

                # Static gesture classification (unchanged)
                res = classifier.classify(hand_landmarks)
                gesture_text = f"Gesture: {res.name}"
                confidence = res.confidence

            now = time.time()
            if palm_x is not None:
                ev = swipe.update(palm_x, palm_y, timestamp=now)
                if ev is not None:
                    last_swipe = ev.name
                    last_swipe_time = now

            # build status
            frame_count += 1
            elapsed = now - start_time
            fps = frame_count / elapsed if elapsed > 0 else 0.0

            status_text = f"Hands: {hand_count}   FPS: {fps:.1f}"
            cv2.putText(
                processed_frame,
                status_text,
                (10, 30),
                cv2.FONT_HERSHEY_SIMPLEX,
                1.0,
                (0, 255, 0) if hand_count > 0 else (0, 0, 255),
                2,
                cv2.LINE_AA,
            )

            # Gesture text
            full_text = f"{gesture_text}   ({confidence:.2f})"
            (tw, th), _ = cv2.getTextSize(full_text, cv2.FONT_HERSHEY_SIMPLEX, 0.9, 2)
            x, y = 10, 65
            cv2.rectangle(processed_frame, (x - 5, y - th - 5), (x + tw + 5, y + 5), (0, 0, 0), -1)
            cv2.putText(
                processed_frame,
                full_text,
                (x, y),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.9,
                (255, 255, 255),
                2,
                cv2.LINE_AA,
            )

            # Swipe text (show briefly)
            if last_swipe and (now - last_swipe_time) < 1.5:
                swipe_text = f"Swipe: {last_swipe}"
                (stw, sth), _ = cv2.getTextSize(swipe_text, cv2.FONT_HERSHEY_SIMPLEX, 0.9, 2)
                sx, sy = 10, 100
                cv2.rectangle(processed_frame, (sx - 5, sy - sth - 5), (sx + stw + 5, sy + 5), (0, 0, 0), -1)
                cv2.putText(
                    processed_frame,
                    swipe_text,
                    (sx, sy),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.9,
                    (0, 240, 255),
                    2,
                    cv2.LINE_AA,
                )

            # Debug overlay: show normalized palm X and a visual indicator
            if palm_x is not None:
                h, w = processed_frame.shape[:2]
                px = int(palm_x * w)
                # small horizontal baseline
                by = 140
                cv2.line(processed_frame, (0, by), (w, by), (30, 30, 30), 1)
                cv2.circle(processed_frame, (px, by), 8, (0, 255, 255), -1)
                debug_text = f"PalmX: {palm_x:.2f}"
                cv2.putText(
                    processed_frame,
                    debug_text,
                    (10, by + 25),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.7,
                    (200, 200, 200),
                    2,
                    cv2.LINE_AA,
                )

            cv2.imshow("GDrop Gesture Swipe v2", processed_frame)

            if cv2.waitKey(1) & 0xFF == ord("q"):
                break

    finally:
        camera.release()
        cv2.destroyAllWindows()
        detector.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
