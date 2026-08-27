import sys
import time
from pathlib import Path

if __package__ in (None, ""):
    repo_root = Path(__file__).resolve().parents[2]
    if str(repo_root) not in sys.path:
        sys.path.insert(0, str(repo_root))

from laptop.gesture.classifier import GestureClassifier

# Self-test helpers (runs without CV/MediaPipe to validate classifier heuristics)
class _LM:
    def __init__(self, x: float, y: float):
        self.x = x
        self.y = y


def _make_open_palm_landmarks() -> list:
    # Baseline wrist at (0.5, 0.6), fingers spread and tips higher (smaller y)
    lm = [_LM(0.5, 0.6) for _ in range(21)]
    # Thumb indices (1..4) - set thumb tip away from index
    lm[1].x, lm[1].y = 0.4, 0.55
    lm[2].x, lm[2].y = 0.35, 0.5
    lm[3].x, lm[3].y = 0.3, 0.45
    lm[4].x, lm[4].y = 0.25, 0.4
    # Index finger
    lm[5].y, lm[6].y = 0.55, 0.45
    lm[7].y, lm[8].y = 0.35, 0.25
    # Middle
    lm[9].y, lm[10].y = 0.55, 0.45
    lm[11].y, lm[12].y = 0.35, 0.25
    # Ring
    lm[13].y, lm[14].y = 0.56, 0.46
    lm[15].y, lm[16].y = 0.36, 0.26
    # Pinky
    lm[17].y, lm[18].y = 0.57, 0.47
    lm[19].y, lm[20].y = 0.37, 0.27
    return lm


def _make_fist_landmarks() -> list:
    lm = [_LM(0.5, 0.5) for _ in range(21)]
    # Tips folded, larger y than PIP
    for tip in (4, 8, 12, 16, 20):
        lm[tip].y = 0.65
    for pip in (6, 10, 14, 18):
        lm[pip].y = 0.55
    # thumb folded near palm
    lm[4].x, lm[4].y = 0.20, 0.62  # thumb tip moved away from index to avoid pinch in synthetic fist
    return lm


def _make_pinch_landmarks() -> list:
    lm = [_LM(0.5, 0.5) for _ in range(21)]
    # place thumb tip very close to index tip
    lm[8].x, lm[8].y = 0.5, 0.4
    lm[4].x, lm[4].y = 0.505, 0.405
    # other fingers relaxed (not fully open)
    for tip in (12, 16, 20):
        lm[tip].y = 0.45
    for pip in (10, 14, 18):
        lm[pip].y = 0.50
    return lm


def run_selftest() -> None:
    classifier = GestureClassifier()
    tests = [
        ("OPEN_PALM", _make_open_palm_landmarks()),
        ("FIST", _make_fist_landmarks()),
        ("PINCH", _make_pinch_landmarks()),
    ]
    print("Running classifier self-tests:")
    for expected, lm in tests:
        res = classifier.classify(lm)
        ok = res.name == expected
        print(f"  Expected={expected:9s}  Got={res.name:9s}  Confidence={res.confidence:.2f}  -> {'PASS' if ok else 'FAIL'}")

    print("Self-test complete. Note: This only validates classifier heuristics in isolation.")



def main() -> int:
    # allow a quick self-test run without camera/model
    if "--selftest" in sys.argv:
        run_selftest()
        return 0

    # Look for model in gesture/models first (common), then laptop/models as fallback
    gesture_model = Path(__file__).resolve().parents[0] / "models" / "hand_landmarker.task"
    laptop_model = Path(__file__).resolve().parents[1] / "models" / "hand_landmarker.task"
    if gesture_model.exists():
        model_path = gesture_model
    else:
        model_path = laptop_model

    import cv2
    from laptop.gesture.detector import HandDetector

    print(f"Using hand landmarker model at: {model_path}")
    detector = HandDetector(model_path=str(model_path), max_hands=1)
    classifier = GestureClassifier()

    camera = cv2.VideoCapture(0)

    if not camera.isOpened():
        print("Unable to open webcam. Please connect a camera and try again.")
        return 1

    start_time = time.time()
    frame_count = 0

    try:
        while True:
            success, frame = camera.read()
            if not success:
                print("Failed to read frame from webcam.")
                break

            processed_frame, hand_count, result = detector.process_frame(frame)

            gesture_text = "Gesture: UNKNOWN"
            confidence = 0.0
            if result and getattr(result, "hand_landmarks", None):
                # use first detected hand
                hand_landmarks = result.hand_landmarks[0]
                res = classifier.classify(hand_landmarks)
                gesture_text = f"Gesture: {res.name}"
                confidence = res.confidence

            frame_count += 1
            elapsed = time.time() - start_time
            fps = frame_count / elapsed if elapsed > 0 else 0.0

            status_text = f"Hands: {hand_count}   FPS: {fps:.1f}"
            # Draw status
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

            # Draw gesture text with a background rectangle for readability
            full_text = f"{gesture_text}   ({confidence:.2f})"
            (tw, th), _ = cv2.getTextSize(full_text, cv2.FONT_HERSHEY_SIMPLEX, 0.9, 2)
            x, y = 10, 65
            # background box
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

            cv2.imshow("GDrop Gesture Classifier v1", processed_frame)

            if cv2.waitKey(1) & 0xFF == ord("q"):
                break

    finally:
        camera.release()
        cv2.destroyAllWindows()
        detector.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
