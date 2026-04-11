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

# =========================================================
# Hyperparameters for Fine-Tuning (OneClassAdversarialAutoencoder)
# =========================================================
# Data Dimensions
SENSOR_INPUT_DIM = 8
FUSION_INPUT_DIM = 29
SEQUENCE_LENGTH = 200
BATCH_SIZE = 32