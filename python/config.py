"""
Centralized configuration for the anomaly detection framework.
"""
from pathlib import Path

# -------------------------------------------------------------------------
# Project Paths
# -------------------------------------------------------------------------
PROJECT_ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets"
ASSETS_DIR.mkdir(parents=True, exist_ok=True)
EXPORT_PATH = PROJECT_ROOT / "python" / "saved_model"

SENSOR_TFLITE_FILE_PATH = ASSETS_DIR / "sensor_model.tflite"
FUSION_TFLITE_FILE_PATH = ASSETS_DIR / "fusion_model.tflite"

# -------------------------------------------------------------------------
# Default Model Hyperparameters
# -------------------------------------------------------------------------
SENSOR_INPUT_DIM = 42  # linearAccel(14) + gyro(14) + totalAccel(14)
FUSION_INPUT_DIM = 56  # sensor(42) + touch(14)

# Backward compatibility or general default
DEFAULT_INPUT_DIM = 20
DEFAULT_LATENT_DIM = 16
DEFAULT_BATCH_SIZE = 32
DEFAULT_DROPOUT_RATE = 0.2
DEFAULT_L2_REG = 1e-5
