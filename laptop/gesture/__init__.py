# Package init kept minimal to avoid heavy imports at package import time.
# Import detector explicitly where needed: `from laptop.gesture.detector import HandDetector`
__all__ = ["HandDetector", "GestureClassifier"]
