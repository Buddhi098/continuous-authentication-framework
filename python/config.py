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

# SENSOR_TFLITE_FILE_PATH = ASSETS_DIR / "sensor_model.tflite"
# SENSOR_TFLITE_FILE_PATH = "D:/research-experiment/model-training-code/sensor_model.tflite"
SENSOR_TFLITE_FILE_PATH = ASSETS_DIR / "sensor_model.tflite"
FUSION_TFLITE_FILE_PATH = ASSETS_DIR / "fusion_model.tflite"

# =========================================================
# Hyperparameters for Fine-Tuning (OneClassAdversarialAutoencoder)
# =========================================================
# Data Dimensions
SENSOR_INPUT_DIM = 12
FUSION_INPUT_DIM = 26
SEQUENCE_LENGTH = 200
BATCH_SIZE = 16

# Latent Space
LATENT_DIM = 128
NOISE_STDDEV = 0.1           # Noise added to inputs during training

# Loss Weights
LAMBDA_REC = 50.0           # Weight of MSE reconstruction loss vs GAN loss

# Learning Rates
LR_ENC_DEC = 1e-4            # Autoencoder learning rate
LR_S_DISC = 1e-4             # Sample discriminator learning rate
LR_L_DISC = 1e-4             # Latent discriminator learning rate

# Attention Module Settings
SE_REDUCTION = 8             # Squeeze-and-Excitation reduction ratio
CBAM_REDUCTION = 8           # CBAM channel reduction ratio
CBAM_SPATIAL_KERNEL = 5      # CBAM spatial convolution kernel size

# Architecture Tweaks
LEAKY_RELU_ALPHA = 0.2       # Slope for LeakyReLU layers

# DUMMY_INPUT_SHAPE = [20, INPUT_DIM, SEQUENCE_LENGTH, 1]

# For LiteRT Model Testing
TFLITE_FILE_PATH = SENSOR_TFLITE_FILE_PATH