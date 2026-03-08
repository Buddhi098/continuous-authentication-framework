"""
Deep Autoencoder with deterministic inference for One-Class Anomaly Detection.
"""
import tensorflow as tf
from tensorflow.keras import regularizers
from typing import Dict, List, Tuple, Any

from .base import BaseAnomalyDetector
from config import (
    DEFAULT_INPUT_DIM, DEFAULT_BATCH_SIZE,
    DEFAULT_DROPOUT_RATE, DEFAULT_L2_REG
)


class DeepAnomalyAutoencoder(BaseAnomalyDetector):
    """
    Deep Autoencoder optimized for one-class anomaly detection.
    Deterministic reconstruction with absolute maximum feature error for scoring.
    """
    def __init__(
        self,
        input_dim: int = DEFAULT_INPUT_DIM,
        batch_size: int = DEFAULT_BATCH_SIZE,
        dropout_rate: float = 0.0,
        l2_reg: float = 1e-5,
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)
        self.dropout_rate = dropout_rate
        
        reg = regularizers.l2(l2_reg)

        # ---------------- Encoder ----------------
        self.enc_dense1 = tf.keras.layers.Dense(64, kernel_regularizer=reg)
        self.enc_norm1 = tf.keras.layers.LayerNormalization()
        self.enc_act1 = tf.keras.layers.LeakyReLU(0.2)

        self.enc_dense2 = tf.keras.layers.Dense(32)
        self.enc_norm2 = tf.keras.layers.LayerNormalization()
        self.enc_act2 = tf.keras.layers.LeakyReLU(0.2)

        self.bottleneck = tf.keras.layers.Dense(16, name="bottleneck", kernel_regularizer=reg)

        # ---------------- Decoder ----------------
        self.dec_dense1 = tf.keras.layers.Dense(32, kernel_regularizer=reg)
        self.dec_norm1 = tf.keras.layers.LayerNormalization()
        self.dec_act1 = tf.keras.layers.LeakyReLU(0.2)

        self.dec_dense2 = tf.keras.layers.Dense(64, kernel_regularizer=reg)
        self.dec_norm2 = tf.keras.layers.LayerNormalization()
        self.dec_act2 = tf.keras.layers.LeakyReLU(0.2)

        self.dec_output = tf.keras.layers.Dense(input_dim, activation="linear", name="reconstruction")

        # ---------------- Optimizer ----------------
        lr_schedule = tf.keras.optimizers.schedules.ExponentialDecay(
            initial_learning_rate=1e-3,
            decay_steps=2000,
            decay_rate=0.9
        )
        self.optimizer = tf.keras.optimizers.Adam(learning_rate=lr_schedule)

    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    def call(self, inputs: tf.Tensor, training: bool = False) -> tf.Tensor:
        # Encode
        x = self.enc_dense1(inputs)
        x = self.enc_norm1(x)
        x = self.enc_act1(x)
        if training:
            x = tf.nn.dropout(x, rate=self.dropout_rate)

        x = self.enc_dense2(x)
        x = self.enc_norm2(x)
        x = self.enc_act2(x)
        if training:
            x = tf.nn.dropout(x, rate=self.dropout_rate)

        # Bottleneck
        encoded = self.bottleneck(x)
        
        # Decode
        x = self.dec_dense1(encoded)
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

    def compute_anomaly_score(self, inputs: tf.Tensor, reconstruction: tf.Tensor) -> tf.Tensor:
        # MSE
        mse = tf.reduce_mean(tf.square(inputs - reconstruction), axis=1)
        # Max Absolute Error for outlier sensitivity
        max_error = tf.reduce_max(tf.abs(inputs - reconstruction), axis=1)
        
        # Combined anomaly score
        return 0.5 * mse + 0.5 * max_error

    def bake_weights(self) -> None:
        self.baked_weights = [tf.constant(w.numpy(), dtype=w.dtype) for w in self.trainable_variables]

    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        reconstruction = self.call(inputs, training=False)
        
        reconstruction_error = tf.reduce_mean(tf.square(inputs - reconstruction), axis=1)
        anomaly_score = self.compute_anomaly_score(inputs, reconstruction)
        
        return {
            "reconstruction": reconstruction,
            "reconstruction_error": reconstruction_error,
            "anomaly_score": anomaly_score
        }

    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        with tf.GradientTape() as tape:
            reconstruction = self.call(inputs, training=True)
            loss = tf.reduce_mean(tf.square(inputs - reconstruction))
            
        grads = tape.gradient(loss, self.trainable_variables)
        self.optimizer.apply_gradients(zip(grads, self.trainable_variables))
        
        return {"loss": loss}

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

    def get_signatures(self) -> Dict[str, Any]:
        signatures = super().get_signatures()
        
        infer_fn = tf.function(self.infer_func).get_concrete_function(
            tf.TensorSpec(shape=[None, self.input_dim], dtype=tf.float32, name="inputs")
        )
        train_fn = tf.function(self.train_func).get_concrete_function(
            tf.TensorSpec(shape=[self.batch_size, self.input_dim], dtype=tf.float32, name="inputs")
        )
        init_fn = tf.function(self.init_model).get_concrete_function(
            tf.TensorSpec(shape=[1], dtype=tf.float32, name="x")
        )
        
        signatures["infer"] = infer_fn
        signatures["train"] = train_fn
        signatures["init_model"] = init_fn
        return signatures
