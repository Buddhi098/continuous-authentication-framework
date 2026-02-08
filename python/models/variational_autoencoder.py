"""
Variational Autoencoder with Skip Connections for Anomaly Detection.
"""
import tensorflow as tf
from tensorflow.keras import regularizers
from typing import Dict, List, Tuple, Any

from .base import BaseAnomalyDetector
from config import (
    DEFAULT_INPUT_DIM, DEFAULT_LATENT_DIM, DEFAULT_BATCH_SIZE,
    DEFAULT_DROPOUT_RATE, DEFAULT_L2_REG
)


# Model-specific constants
MASK_RATIO = 0.3        # 30% of latent vector will be masked
BETA_VAE = 0.1          # KL weight (low for reconstruction focus)
SKIP_PROJECTION = 32    # Skip connection hidden dim


class VariationalSensorAutoencoder(BaseAnomalyDetector):
    """
    Variational Autoencoder with skip connections and multi-scale anomaly scoring.
    
    Features:
    - VAE-style bottleneck with reparameterization trick
    - Latent masking during training for robustness
    - Skip connections for better gradient flow
    - Multi-scale anomaly scoring
    """
    
    def __init__(
        self,
        input_dim: int = DEFAULT_INPUT_DIM,
        latent_dim: int = DEFAULT_LATENT_DIM,
        batch_size: int = DEFAULT_BATCH_SIZE,
        dropout_rate: float = DEFAULT_DROPOUT_RATE,
        l2_reg: float = DEFAULT_L2_REG,
        mask_ratio: float = MASK_RATIO,
        beta: float = BETA_VAE,
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)
        self.latent_dim = latent_dim
        self.dropout_rate = dropout_rate
        self.mask_ratio = mask_ratio
        self.beta = beta
        
        reg = regularizers.l2(l2_reg)

        # ---------------- Encoder ----------------
        self.enc_dense1 = tf.keras.layers.Dense(64, kernel_regularizer=reg)
        self.enc_norm1 = tf.keras.layers.LayerNormalization()
        self.enc_act1 = tf.keras.layers.LeakyReLU(0.2)

        self.enc_dense2 = tf.keras.layers.Dense(32, kernel_regularizer=reg)
        self.enc_norm2 = tf.keras.layers.LayerNormalization()
        self.enc_act2 = tf.keras.layers.LeakyReLU(0.2)

        # VAE-style bottleneck: mean and log-variance
        self.z_mean = tf.keras.layers.Dense(latent_dim, name="z_mean", kernel_regularizer=reg)
        self.z_log_var = tf.keras.layers.Dense(latent_dim, name="z_log_var", kernel_regularizer=reg)

        # ---------------- Skip Connection Projection ----------------
        self.skip_proj = tf.keras.layers.Dense(SKIP_PROJECTION, kernel_regularizer=reg)

        # ---------------- Decoder ----------------
        self.dec_dense1 = tf.keras.layers.Dense(32, kernel_regularizer=reg)
        self.dec_norm1 = tf.keras.layers.LayerNormalization()
        self.dec_act1 = tf.keras.layers.LeakyReLU(0.2)

        self.dec_dense2 = tf.keras.layers.Dense(64, kernel_regularizer=reg)
        self.dec_norm2 = tf.keras.layers.LayerNormalization()
        self.dec_act2 = tf.keras.layers.LeakyReLU(0.2)

        self.dec_output = tf.keras.layers.Dense(input_dim, activation="linear", name="reconstruction")

        # ---------------- Optimizer ----------------
        lr_schedule = tf.keras.optimizers.schedules.CosineDecay(
            initial_learning_rate=2e-3,
            decay_steps=5000,
            alpha=0.01
        )
        self.optimizer = tf.keras.optimizers.AdamW(learning_rate=lr_schedule, weight_decay=1e-4)

    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    # ------------------------------------------------------------------
    # Reparameterization Trick
    # ------------------------------------------------------------------
    def reparameterize(self, z_mean: tf.Tensor, z_log_var: tf.Tensor, training: bool = False) -> tf.Tensor:
        """Sample from latent distribution using reparameterization trick."""
        if training:
            epsilon = tf.random.normal(shape=tf.shape(z_mean))
            return z_mean + tf.exp(0.5 * z_log_var) * epsilon
        return z_mean  # Use mean during inference for deterministic output

    # ------------------------------------------------------------------
    # Latent Masking Function
    # ------------------------------------------------------------------
    def mask_latent(self, latent: tf.Tensor, training: bool = False) -> tf.Tensor:
        if training:
            mask = tf.random.uniform(shape=tf.shape(latent)) > self.mask_ratio
            return latent * tf.cast(mask, tf.float32)
        return latent

    # ------------------------------------------------------------------
    # Encoder Forward Pass
    # ------------------------------------------------------------------
    def encode(self, inputs: tf.Tensor, training: bool = False) -> Tuple[tf.Tensor, tf.Tensor, tf.Tensor]:
        x = self.enc_dense1(inputs)
        x = self.enc_norm1(x)
        x = self.enc_act1(x)
        if training:
            x = tf.nn.dropout(x, rate=self.dropout_rate)

        x = self.enc_dense2(x)
        x = self.enc_norm2(x)
        enc_intermediate = self.enc_act2(x)  # Store for skip connection
        if training:
            enc_intermediate = tf.nn.dropout(enc_intermediate, rate=self.dropout_rate)

        # VAE bottleneck
        z_mean = self.z_mean(enc_intermediate)
        z_log_var = self.z_log_var(enc_intermediate)
        
        return z_mean, z_log_var, enc_intermediate

    # ------------------------------------------------------------------
    # Decoder Forward Pass
    # ------------------------------------------------------------------
    def decode(self, z: tf.Tensor, enc_intermediate: tf.Tensor, training: bool = False) -> tf.Tensor:
        # Skip connection projection
        skip = self.skip_proj(enc_intermediate)
        
        x = self.dec_dense1(z)
        x = x + skip  # Add skip connection
        x = self.dec_norm1(x)
        x = self.dec_act1(x)
        if training:
            x = tf.nn.dropout(x, rate=self.dropout_rate)

        x = self.dec_dense2(x)
        x = self.dec_norm2(x)
        x = self.dec_act2(x)
        if training:
            x = tf.nn.dropout(x, rate=self.dropout_rate)

        return self.dec_output(x)

    # ------------------------------------------------------------------
    # Full Forward Pass
    # ------------------------------------------------------------------
    def call(self, inputs: tf.Tensor, training: bool = False) -> Tuple[tf.Tensor, tf.Tensor, tf.Tensor]:
        # Encode
        z_mean, z_log_var, enc_intermediate = self.encode(inputs, training)
        
        # Sample from latent distribution
        z = self.reparameterize(z_mean, z_log_var, training)
        
        # Mask latent vector during training
        z_masked = self.mask_latent(z, training)
        
        # Decode with skip connection
        reconstruction = self.decode(z_masked, enc_intermediate, training)
        
        return reconstruction, z_mean, z_log_var

    # ------------------------------------------------------------------
    # Multi-Scale Anomaly Scoring
    # ------------------------------------------------------------------
    def compute_anomaly_score(
        self,
        inputs: tf.Tensor,
        reconstruction: tf.Tensor,
        z_mean: tf.Tensor,
        z_log_var: tf.Tensor
    ) -> tf.Tensor:
        """
        Compute composite anomaly score combining:
        1. Reconstruction error (MSE)
        2. Latent space regularity (KL-based)
        3. Feature deviation (max per-feature error)
        """
        # 1. Reconstruction error (feature-wise MSE)
        mse = tf.reduce_mean(tf.square(inputs - reconstruction), axis=1)
        
        # 2. Latent space regularity (deviation from standard normal)
        kl_score = tf.reduce_sum(tf.square(z_mean) + tf.exp(z_log_var), axis=1)
        
        # 3. Feature deviation (max per-feature error for outlier sensitivity)
        max_error = tf.reduce_max(tf.abs(inputs - reconstruction), axis=1)
        
        # Weighted combination (tuned for anomaly detection)
        anomaly_score = 0.5 * mse + 0.3 * kl_score + 0.2 * max_error
        
        return anomaly_score

    # ------------------------------------------------------------------
    # Required Interface Methods
    # ------------------------------------------------------------------
    def bake_weights(self) -> None:
        """Store current weights as constants for initialization."""
        self.baked_weights = [tf.constant(w.numpy(), dtype=w.dtype) for w in self.trainable_variables]

    @tf.function(input_signature=[tf.TensorSpec(shape=[None, DEFAULT_INPUT_DIM], dtype=tf.float32)])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        reconstruction, z_mean, z_log_var = self.call(inputs, training=False)
        
        # Standard reconstruction error (backward compatible)
        reconstruction_error = tf.reduce_mean(tf.square(inputs - reconstruction), axis=1)
        
        # Multi-scale anomaly score
        anomaly_score = self.compute_anomaly_score(inputs, reconstruction, z_mean, z_log_var)
        
        return {
            "reconstruction": reconstruction,
            "reconstruction_error": reconstruction_error,
            "anomaly_score": anomaly_score,
            "z_mean": z_mean
        }

    @tf.function(input_signature=[tf.TensorSpec(shape=[DEFAULT_BATCH_SIZE, DEFAULT_INPUT_DIM], dtype=tf.float32)])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        with tf.GradientTape() as tape:
            reconstruction, z_mean, z_log_var = self.call(inputs, training=True)
            
            # Reconstruction loss (MSE)
            mse_loss = tf.reduce_mean(tf.square(inputs - reconstruction))
            
            # KL divergence (regularization toward standard normal)
            kl_loss = -0.5 * tf.reduce_mean(
                1 + z_log_var - tf.square(z_mean) - tf.exp(z_log_var)
            )
            
            # Combined loss (beta-VAE style)
            total_loss = mse_loss + self.beta * kl_loss
            
        grads = tape.gradient(total_loss, self.trainable_variables)
        self.optimizer.apply_gradients(zip(grads, self.trainable_variables))
        
        return {"loss": total_loss, "mse": mse_loss, "kl": kl_loss}

    @tf.function(input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32)])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        if self.baked_weights:
            for var, baked in zip(self.trainable_variables, self.baked_weights):
                var.assign(baked)
        if hasattr(self.optimizer, "variables"):
            for v in self.optimizer.variables:
                v.assign(tf.zeros_like(v))
        return {"status": tf.constant(1.0, dtype=tf.float32)}

    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        return {f"val_{i}": v for i, v in enumerate(self.trainable_variables)}

    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        for i, var in enumerate(self.trainable_variables):
            key = f"val_{i}"
            if key in kwargs:
                var.assign(kwargs[key])
        if hasattr(self.optimizer, "variables"):
            for v in self.optimizer.variables:
                v.assign(tf.zeros_like(v))
        return {"status": tf.constant(1.0, dtype=tf.float32)}
