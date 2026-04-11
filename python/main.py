"""
Main entry point for model conversion.

Usage:
    python main.py
"""
import tensorflow as tf
from config import EXPORT_PATH, SENSOR_TFLITE_FILE_PATH, FUSION_TFLITE_FILE_PATH, SENSOR_INPUT_DIM, FUSION_INPUT_DIM ,SEQUENCE_LENGTH , BATCH_SIZE
from converters import TFLiteConverter
# from models.oca_autoencoder_sensor import HybridAASensor
# from models.oca_autoencoder_fused import OneClassAdversarialAutoencoderFused
from models.sensor import SensorAuthenticator
from models.fusion import MaskedFusionAuthenticator


def main():
    print(f"TensorFlow Version: {tf.__version__}")
    print("=" * 60)

    # Convert to LiteRT model
    converter = TFLiteConverter()

    # Create Sensor model
    print("Initializing Deep Anomaly Autoencoder (Sensor Only)...")
    sensor_model = SensorAuthenticator(input_dim=SENSOR_INPUT_DIM, seq_len=SEQUENCE_LENGTH)
    
    sensor_tflite_path = converter.convert(
        model=sensor_model,
        export_path=EXPORT_PATH / "sensor",
        output_path=SENSOR_TFLITE_FILE_PATH,
        input_shape = (BATCH_SIZE, SEQUENCE_LENGTH, SENSOR_INPUT_DIM )
    )
    
    print("=" * 60)
    print(f"SUCCESS: LiteRT Sensor model saved at: {sensor_tflite_path}")

    # Create Fusion model
    print("Initializing Deep Anomaly Autoencoder (Fusion)...")
    fusion_model = MaskedFusionAuthenticator(input_dim=FUSION_INPUT_DIM)
    
    # Convert to LiteRT model
    fusion_tflite_path = converter.convert(
        model=fusion_model,
        export_path=EXPORT_PATH / "fusion",
        output_path=FUSION_TFLITE_FILE_PATH,
        input_shape = (BATCH_SIZE, FUSION_INPUT_DIM )
    )
    
    print(f"SUCCESS: LiteRT Fusion model saved at: {fusion_tflite_path}")

if __name__ == "__main__":
    main()
