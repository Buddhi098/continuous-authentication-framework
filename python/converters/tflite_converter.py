"""
Generic TFLite converter for anomaly detection models.
"""
import tensorflow as tf
import shutil
from pathlib import Path
from typing import Optional, List
from models.base import BaseAnomalyDetector
from config import BATCH_SIZE

class TFLiteConverter:
    """
    Generic TFLite converter that works with any BaseAnomalyDetector model.
    
    Supports:
    - SavedModel export with custom signatures
    - LiteRT conversion with optional quantization
    - Full pipeline from model to .tflite file
    """
    
    def __init__(self, enable_resource_variables: bool = True):
        """
        Initialize the converter.
        
        Args:
            enable_resource_variables: Enable resource variables for on-device training
        """
        self.enable_resource_variables = enable_resource_variables
    
    def export_saved_model(
            self,
            model: BaseAnomalyDetector,
            export_path: Path,
            clean_existing: bool = True
        ) -> Path:
        """
        Export model as SavedModel with signatures.

        Args:
            model: The anomaly detector model to export
            export_path: Directory to save the model
            clean_existing: Remove existing directory if present

        Returns:
            Path to the exported SavedModel
        """

        export_path = Path(export_path)

        if clean_existing and export_path.exists():
            shutil.rmtree(export_path)

        # -------------------------------------------------
        # Dynamically determine model input shape
        # -------------------------------------------------
        if hasattr(model, "input_shape") and model.input_shape is not None:
            input_shape = list(model.input_shape)
            input_shape[0] = BATCH_SIZE  # Replace batch dimension with 1
        else:
            raise ValueError("Model input shape is not defined.")

        # Build model using dummy input
        dummy_input = tf.zeros(input_shape)

        model.train_func(dummy_input)
        model.bake_weights()

        # Get signatures from model
        signatures = model.get_signatures()

        print(f"Exporting SavedModel to {export_path}...")
        tf.saved_model.save(model, str(export_path), signatures=signatures)

        return export_path
    
    def convert_to_tflite(
        self,
        saved_model_path: Path,
        output_path: Path,
        signature_keys: Optional[List[str]] = None,
        quantization: Optional[str] = None
    ) -> Path:
        """
        Convert SavedModel to LiteRT format.
        
        Args:
            saved_model_path: Path to the SavedModel directory
            output_path: Path for the output .tflite file
            signature_keys: List of signatures to include (default: all)
            quantization: Quantization mode: None, "dynamic", "float16"
            
        Returns:
            Path to the generated .tflite file
        """
        saved_model_path = Path(saved_model_path)
        output_path = Path(output_path)
        
        # Remove existing tflite file
        if output_path.exists():
            output_path.unlink()
        
        print(f"Converting to LiteRT model...")
        
        # Set up converter
        if signature_keys:
            converter = tf.lite.TFLiteConverter.from_saved_model(
                str(saved_model_path),
                signature_keys=signature_keys
            )
        else:
            converter = tf.lite.TFLiteConverter.from_saved_model(str(saved_model_path))
        
        # Configure ops
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS
        ]
        
        if self.enable_resource_variables:
            converter.experimental_enable_resource_variables = True
        
        # Apply quantization if requested
        if quantization == "dynamic":
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
        elif quantization == "float16":
            converter.optimizations = [tf.lite.Optimize.DEFAULT]
            converter.target_spec.supported_types = [tf.float16]
        
        # Convert and save
        tflite_model = converter.convert()
        
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "wb") as f:
            f.write(tflite_model)
        
        print(f"LiteRT model saved to: {output_path}")
        return output_path
    
    def convert(
        self,
        model: BaseAnomalyDetector,
        export_path: Path,
        output_path: Path,
        quantization: Optional[str] = None
    ) -> Path:
        """
        Full conversion pipeline: export SavedModel and convert to TFLite.
        
        Args:
            model: The anomaly detector model to convert
            export_path: Directory for intermediate SavedModel
            output_path: Path for the output .tflite file
            quantization: Quantization mode: None, "dynamic", "float16"
            
        Returns:
            Path to the generated .tflite file
        """
        # Export SavedModel
        saved_model_path = self.export_saved_model(model, export_path)
        
        # Convert to TFLite
        return self.convert_to_tflite(
            saved_model_path=saved_model_path,
            output_path=output_path,
            signature_keys=model.signature_keys,
            quantization=quantization
        )
