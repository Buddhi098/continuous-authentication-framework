"""
Main entry point for model conversion.

Usage:
    python main.py
"""
import tensorflow as tf
from config import EXPORT_PATH, SENSOR_TFLITE_FILE_PATH, FUSION_TFLITE_FILE_PATH, SENSOR_INPUT_DIM, FUSION_INPUT_DIM , TEST_TFLITE_FILE_PATH
from converters import TFLiteConverter
from models.oca_autoencoder_sensor import HybridAASensor
from models.oca_autoencoder_fused import OneClassAdversarialAutoencoderFused
# from models.temp_2_sensor import HybridAASensor
# from models.temp_2_fusion import HybridAAFusion
# from models.temp import RelativeAttentionAdversarialAutoencoder

def main():
    print(f"TensorFlow Version: {tf.__version__}")
    print("=" * 60)

    # Convert to LiteRT model
    converter = TFLiteConverter()

    # Create Sensor model
    print("Initializing Deep Anomaly Autoencoder (Sensor Only)...")
    sensor_model = HybridAASensor()
    
    sensor_tflite_path = converter.convert(
        model=sensor_model,
        export_path=EXPORT_PATH / "sensor",
        output_path=TEST_TFLITE_FILE_PATH
    )
    
    print("=" * 60)
    print(f"SUCCESS: LiteRT Sensor model saved at: {sensor_tflite_path}")

    # # Create Fusion model
    # print("Initializing Deep Anomaly Autoencoder (Fusion)...")
    # fusion_model = HybridAAFusion()
    
    # # Convert to LiteRT model
    # fusion_tflite_path = converter.convert(
    #     model=fusion_model,
    #     export_path=EXPORT_PATH / "fusion",
    #     output_path=FUSION_TFLITE_FILE_PATH
    # )
    
    # print(f"SUCCESS: LiteRT Fusion model saved at: {fusion_tflite_path}")

if __name__ == "__main__":
    main()
