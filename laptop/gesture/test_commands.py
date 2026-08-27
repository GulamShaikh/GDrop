import sys
import time
from pathlib import Path

if __package__ in (None, ""):
    repo_root = Path(__file__).resolve().parents[2]
    if str(repo_root) not in sys.path:
        sys.path.insert(0, str(repo_root))

from laptop.gesture.classifier import GestureClassifier
from laptop.gesture.swipe import SwipeDetector
from laptop.gesture.commands import GestureCommandBridge

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
    swipe = SwipeDetector(min_distance=0.12, min_duration=0.05, max_duration=1.0, sample_window=1.0, cooldown=0.8, horizontal_vs_vertical_ratio=1.2)
    bridge = GestureCommandBridge(debounce=0.6)

    camera = cv2.VideoCapture(0)

    if not camera.isOpened():
        print("Unable to open webcam. Please connect a camera and try again.")
        return 1

    start_time = time.time()
    frame_count = 0
    last_swipe = None
    last_command = None
    last_command_time = 0.0

    try:
        while True:
            success, frame = camera.read()
            if not success:
                print("Failed to read frame from webcam.")
                break

            processed_frame, hand_count, result = detector.process_frame(frame)

            gesture_text = "Gesture: UNKNOWN"
            confidence = 0.0

            palm_x = None
            palm_y = None

            if result and getattr(result, "hand_landmarks", None):
                hand_landmarks = result.hand_landmarks[0]
                try:
                    lm0 = hand_landmarks[0]
                    palm_x = lm0.x
                    palm_y = lm0.y
                except Exception:
                    xs = [lm.x for lm in hand_landmarks]
                    ys = [lm.y for lm in hand_landmarks]
                    if xs:
                        palm_x = sum(xs) / len(xs)
                        palm_y = sum(ys) / len(ys)

                res = classifier.classify(hand_landmarks)
                gesture_text = f"Gesture: {res.name}"
                confidence = res.confidence

            now = time.time()
            if palm_x is not None:
                ev = swipe.update(palm_x, palm_y, timestamp=now)
                if ev is not None:
                    last_swipe = ev.name
                    # convert to command
                    cmd = bridge.process_swipe(ev)
                    if cmd is not None:
                        last_command = cmd.name
                        last_command_time = now
                        # attempt to send command to configured endpoint
                        from laptop.gesture.commands import CommandSender

                        sender = CommandSender()
                        ok = sender.send(cmd)
                        # show result briefly
                        last_command = f"{cmd.name}{' (sent)' if ok else ' (failed)'}"
                        last_command_time = now

            frame_count += 1
            elapsed = now - start_time
            fps = frame_count / elapsed if elapsed > 0 else 0.0

            status_text = f"Hands: {hand_count}   FPS: {fps:.1f}"
            cv2.putText(processed_frame, status_text, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 255, 0) if hand_count > 0 else (0, 0, 255), 2, cv2.LINE_AA)

            full_text = f"{gesture_text}   ({confidence:.2f})"
            (tw, th), _ = cv2.getTextSize(full_text, cv2.FONT_HERSHEY_SIMPLEX, 0.9, 2)
            x, y = 10, 65
            cv2.rectangle(processed_frame, (x - 5, y - th - 5), (x + tw + 5, y + 5), (0, 0, 0), -1)
            cv2.putText(processed_frame, full_text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (255, 255, 255), 2, cv2.LINE_AA)

            # show last swipe
            if last_swipe and (now - (last_command_time if last_command_time else now)) < 2.0:
                swipe_text = f"Swipe: {last_swipe}"
                (stw, sth), _ = cv2.getTextSize(swipe_text, cv2.FONT_HERSHEY_SIMPLEX, 0.9, 2)
                sx, sy = 10, 100
                cv2.rectangle(processed_frame, (sx - 5, sy - sth - 5), (sx + stw + 5, sy + 5), (0, 0, 0), -1)
                cv2.putText(processed_frame, swipe_text, (sx, sy), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 240, 255), 2, cv2.LINE_AA)

            # show last command
            if last_command and (now - last_command_time) < 2.0:
                cmd_text = f"Cmd: {last_command}"
                (ctw, cth), _ = cv2.getTextSize(cmd_text, cv2.FONT_HERSHEY_SIMPLEX, 0.9, 2)
                cx, cy = 10, 135
                cv2.rectangle(processed_frame, (cx - 5, cy - cth - 5), (cx + ctw + 5, cy + 5), (0, 0, 0), -1)
                cv2.putText(processed_frame, cmd_text, (cx, cy), cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 200, 0), 2, cv2.LINE_AA)

            # Debug palm X
            if palm_x is not None:
                h, w = processed_frame.shape[:2]
                px = int(palm_x * w)
                by = 170
                cv2.line(processed_frame, (0, by), (w, by), (30, 30, 30), 1)
                cv2.circle(processed_frame, (px, by), 8, (0, 255, 255), -1)
                debug_text = f"PalmX: {palm_x:.2f}"
                cv2.putText(processed_frame, debug_text, (10, by + 25), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (200, 200, 200), 2, cv2.LINE_AA)

            cv2.imshow("GDrop Gesture Commands v1", processed_frame)

            if cv2.waitKey(1) & 0xFF == ord("q"):
                break

    finally:
        camera.release()
        cv2.destroyAllWindows()
        detector.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
