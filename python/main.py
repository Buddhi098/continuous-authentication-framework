"""
Main entry point for model conversion.

Usage:
    python main.py
"""
import tensorflow as tf

from config import EXPORT_PATH, TFLITE_FILE_PATH
from models import VariationalSensorAutoencoder
from converters import TFLiteConverter


def main():
    print(f"TensorFlow Version: {tf.__version__}")
    print("=" * 60)
    
    # Create model
    print("Initializing Variational Sensor Autoencoder...")
    model = VariationalSensorAutoencoder()
    
    # Convert to LiteRT model
    converter = TFLiteConverter()
    tflite_path = converter.convert(
        model=model,
        export_path=EXPORT_PATH,
        output_path=TFLITE_FILE_PATH
    )
    
    print("=" * 60)
    print(f"SUCCESS: LiteRT model saved at: {tflite_path}")


if __name__ == "__main__":
    main()
