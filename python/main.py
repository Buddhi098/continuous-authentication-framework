"""
Main entry point for model conversion.

Usage:
    python main.py
"""
import tensorflow as tf

from config import EXPORT_PATH, SENSOR_TFLITE_FILE_PATH, FUSION_TFLITE_FILE_PATH, SENSOR_INPUT_DIM, FUSION_INPUT_DIM
from models import VariationalSensorAutoencoder
from converters import TFLiteConverter


def main():
    print(f"TensorFlow Version: {tf.__version__}")
    print("=" * 60)
    
    # Create Sensor model
    print("Initializing Variational Sensor Autoencoder (Sensor Only)...")
    sensor_model = VariationalSensorAutoencoder(input_dim=SENSOR_INPUT_DIM)
    
    # Convert to LiteRT model
    converter = TFLiteConverter()
    sensor_tflite_path = converter.convert(
        model=sensor_model,
        export_path=EXPORT_PATH / "sensor",
        output_path=SENSOR_TFLITE_FILE_PATH
    )
    
    print("=" * 60)
    print(f"SUCCESS: LiteRT Sensor model saved at: {sensor_tflite_path}")

    # Create Fusion model
    print("Initializing Variational Sensor Autoencoder (Fusion)...")
    fusion_model = VariationalSensorAutoencoder(input_dim=FUSION_INPUT_DIM)
    
    # Convert to LiteRT model
    fusion_tflite_path = converter.convert(
        model=fusion_model,
        export_path=EXPORT_PATH / "fusion",
        output_path=FUSION_TFLITE_FILE_PATH
    )
    
    print("=" * 60)
    print(f"SUCCESS: LiteRT Fusion model saved at: {fusion_tflite_path}")


if __name__ == "__main__":
    main()
