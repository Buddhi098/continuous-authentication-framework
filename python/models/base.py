"""
Abstract base class for one-class anomaly detection models.
"""
import tensorflow as tf
from abc import abstractmethod
from typing import Dict, List, Any


class BaseAnomalyDetector(tf.keras.Model):
    """
    Abstract base class for all anomaly detection models.
    
    All models must implement the required methods to ensure compatibility
    with the TFLite conversion pipeline and on-device training/inference.
    """
    
    def __init__(self, input_dim: int, batch_size: int = 32, **kwargs):
        super().__init__(**kwargs)
        self.input_dim = input_dim
        self.batch_size = batch_size
        self.baked_weights: List[tf.Tensor] = []
    
    @property
    def persistent_weights(self) -> List[tf.Variable]:
        """Return variables needed for saving/restoring, excluding untracked RNN/Dropout RNG seeds."""
        return [v for v in self.variables if "seed_generator" not in v.name]
    
    @property
    @abstractmethod
    def signature_keys(self) -> List[str]:
        """
        Return list of signature names for SavedModel export.
        Example: ["train", "infer", "init_model", "save", "restore"]
        """
        pass
    
    @abstractmethod
    def call(self, inputs: tf.Tensor, training: bool = False) -> Any:
        """Forward pass of the model."""
        pass
    
    @abstractmethod
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        """
        Training step function.
        Must be decorated with @tf.function with appropriate input_signature.
        Returns dict with loss values.
        """
        pass
    
    @abstractmethod
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        """
        Inference function.
        Must be decorated with @tf.function with appropriate input_signature.
        Returns dict with predictions/scores.
        """
        pass
    
    @abstractmethod
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        """
        Initialize/reset model weights.
        Must be decorated with @tf.function with appropriate input_signature.
        """
        pass
    
    @abstractmethod
    def bake_weights(self) -> None:
        """Store current weights as constants for initialization."""
        pass
    
    @abstractmethod
    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        """
        Return model weights as a dictionary.
        Used for on-device weight persistence.
        """
        pass
    
    @abstractmethod
    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        """
        Restore model weights from a dictionary.
        Used for on-device weight persistence.
        """
        pass
    
    def get_signatures(self) -> Dict[str, Any]:
        """
        Return dictionary of signatures for SavedModel export.
        Subclasses can override to customize signatures.
        """
        # Get concrete functions for save/restore
        save_fn = tf.function(self.save_weights_func).get_concrete_function(
            tf.TensorSpec(shape=[1], dtype=tf.float32, name="x")
        )
        
        restore_specs = {
            f"val_{i}": tf.TensorSpec(shape=v.shape, dtype=v.dtype, name=f"val_{i}")
            for i, v in enumerate(self.persistent_weights)
        }
        restore_fn = tf.function(self.restore_weights_func).get_concrete_function(**restore_specs)
        
        return {
            "train": self.train_func,
            "infer": self.infer_func,
            "init_model": self.init_model,
            "save": save_fn,
            "restore": restore_fn
        }
