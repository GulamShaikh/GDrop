import sys
import time
from pathlib import Path

import cv2

if __package__ in (None, ""):
    repo_root = Path(__file__).resolve().parents[2]
    if str(repo_root) not in sys.path:
        sys.path.insert(0, str(repo_root))

from laptop.gesture.detector import HandDetector


def main() -> int:
    # Look for model in gesture/models first (common), then laptop/models as fallback
    gesture_model = Path(__file__).resolve().parents[0] / "models" / "hand_landmarker.task"
    laptop_model = Path(__file__).resolve().parents[1] / "models" / "hand_landmarker.task"
    if gesture_model.exists():
        model_path = gesture_model
    else:
        model_path = laptop_model
    print(f"Using hand landmarker model at: {model_path}")
    detector = HandDetector(model_path=str(model_path), max_hands=1)
    camera = cv2.VideoCapture(0)

    if not camera.isOpened():
        print("Unable to open webcam. Please connect a camera and try again.")
        return 1

    start_time = time.time()
    frame_count = 0

    while True:
        success, frame = camera.read()
        if not success:
            print("Failed to read frame from webcam.")
            break

        processed_frame, hand_count, _ = detector.process_frame(frame)

        frame_count += 1
        elapsed = time.time() - start_time
        if elapsed > 0:
            fps = frame_count / elapsed
        else:
            fps = 0.0

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

        cv2.imshow("GDrop Gesture Test", processed_frame)

        if cv2.waitKey(1) & 0xFF == ord("q"):
            break

    camera.release()
    cv2.destroyAllWindows()
    detector.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
