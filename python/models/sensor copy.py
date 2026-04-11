import tensorflow as tf
from abc import abstractmethod
from typing import Dict, List, Any
from .base import BaseAnomalyDetector
from config import (
    SEQUENCE_LENGTH, BATCH_SIZE, SENSOR_INPUT_DIM
)

# ------------------------------------------------------------------
# Mobile-Optimized Implementation
# ------------------------------------------------------------------

class MobileSpatioTemporalAutoencoder(BaseAnomalyDetector):
    """
    A lightweight, Fully Convolutional Autoencoder tailored for on-device mobile training.
    Trains on legitimate user sensor data. Imposters will yield a high reconstruction error.
    """
    def __init__(self, seq_len: int, input_dim: int, latent_dim: int = 8, batch_size: int = 32, learning_rate: float = 1e-3, **kwargs):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)
        self.seq_len = seq_len
        self.latent_dim = latent_dim

        # --- 1. Spatial Attention Layer ---
        # Pools across time to learn which *sensors* are consistently important
        self.spatial_att_dense = tf.keras.layers.Dense(input_dim, activation='sigmoid')

        # --- 2. Encoder ---
        self.enc_conv1 = tf.keras.layers.Conv1D(32, kernel_size=3, padding='same', activation='relu')

        # --- 3. Temporal Attention Layer ---
        # Pools across features to apply a gated window to important *timesteps*
        self.temporal_att_conv = tf.keras.layers.Conv1D(1, kernel_size=3, padding='same', activation='sigmoid')

        self.enc_conv2 = tf.keras.layers.Conv1D(16, kernel_size=3, padding='same', activation='relu')

        # --- 4. Fully Convolutional Bottleneck (Latent Space) ---
        # Avoids Flatten() to ensure shape-safety during TFLite conversion
        self.bottleneck = tf.keras.layers.Conv1D(latent_dim, kernel_size=1, padding='same', activation='relu')

        # --- 5. Decoder ---
        self.dec_conv1 = tf.keras.layers.Conv1D(16, kernel_size=3, padding='same', activation='relu')
        self.dec_conv2 = tf.keras.layers.Conv1D(32, kernel_size=3, padding='same', activation='relu')
        self.dec_out = tf.keras.layers.Conv1D(input_dim, kernel_size=3, padding='same', activation='linear')

        # Optimizer and Loss
        self.optimizer = tf.keras.optimizers.Adam(learning_rate=learning_rate)
        self.loss_fn = tf.keras.losses.MeanSquaredError()

        # Initialize weights by passing a dummy tensor, allowing them to be baked
        self._build_model()
        self.bake_weights()

    def _build_model(self):
        """Forces the allocation of weights by pushing a dummy batch through."""
        dummy_x = tf.zeros((1, self.seq_len, self.input_dim))
        self(dummy_x)

    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    def call(self, inputs: tf.Tensor, training: bool = False) -> Any:
        # Expected input shape: [Batch, Time, Sensors]
        
        # Spatial Attention
        avg_pool_s = tf.reduce_mean(inputs, axis=1, keepdims=True)  # [B, 1, F]
        s_att = self.spatial_att_dense(avg_pool_s)                  # [B, 1, F]
        x = inputs * s_att                                          # [B, T, F]

        # First Encoding stage
        x = self.enc_conv1(x)                                       # [B, T, 32]

        # Temporal Attention
        avg_pool_t = tf.reduce_mean(x, axis=-1, keepdims=True)      # [B, T, 1]
        t_att = self.temporal_att_conv(avg_pool_t)                  # [B, T, 1]
        x = x * t_att                                               # [B, T, 32]

        # Deep Encoding to Bottleneck
        x = self.enc_conv2(x)                                       # [B, T, 16]
        latent = self.bottleneck(x)                                 # [B, T, latent_dim]

        # Decoding stage
        x_dec = self.dec_conv1(latent)                              # [B, T, 16]
        x_dec = self.dec_conv2(x_dec)                               # [B, T, 32]
        reconstructed = self.dec_out(x_dec)                         # [B, T, F]

        return reconstructed

    # Dynamic shapes None are used for TFLite flexibility, but rank is strictly defined
    @tf.function(input_signature=[tf.TensorSpec(shape=[BATCH_SIZE, SEQUENCE_LENGTH, SENSOR_INPUT_DIM], dtype=tf.float32, name="inputs")])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        """Performs one step of on-device training."""
        with tf.GradientTape() as tape:
            reconstructed = self(inputs, training=True)
            loss = self.loss_fn(inputs, reconstructed)

        gradients = tape.gradient(loss, self.trainable_variables)
        self.optimizer.apply_gradients(zip(gradients, self.trainable_variables))
        
        return {"loss": loss}

    @tf.function(input_signature=[tf.TensorSpec(shape=[None, SEQUENCE_LENGTH, SENSOR_INPUT_DIM], dtype=tf.float32, name="inputs")])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        """
        Runs inference. The Mean Squared Error between the input and 
        the reconstruction is returned as the imposter anomaly score.
        """
        reconstructed = self(inputs, training=False)
        
        # Calculate anomaly score per sample in the batch
        # Higher score = Higher chance of being an imposter
        anomaly_scores = tf.reduce_mean(tf.square(inputs - reconstructed), axis=[1, 2])
        
        return {
            "anomaly_score": anomaly_scores, 
            "reconstructions": reconstructed
        }

    @tf.function(input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32, name="x")])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        """Restores the model to its original baked/untrained state."""
        for i, var in enumerate(self.persistent_weights):
            var.assign(self.baked_weights[i])
        return {"status": tf.constant(1.0)}

    def bake_weights(self) -> None:
        """Saves a snapshot of initial weights in memory."""
        self.baked_weights = [tf.constant(v.numpy()) for v in self.persistent_weights]

    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        """Called by TFLite runner to save weights to device storage."""
        return {f"val_{i}": v for i, v in enumerate(self.persistent_weights)}

    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        """Called by TFLite runner to load weights from device storage."""
        for i, var in enumerate(self.persistent_weights):
            var.assign(kwargs[f"val_{i}"])
        return {"status": tf.constant(1.0)}