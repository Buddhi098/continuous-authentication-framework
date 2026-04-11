import tensorflow as tf
from typing import Dict, Any, List
import numpy as np
from .base import BaseAnomalyDetector
from config import (
    SEQUENCE_LENGTH, BATCH_SIZE, SENSOR_INPUT_DIM
)


# ==========================================================
# 3. ENHANCED SENSOR AUTHENTICATOR
# ==========================================================
class SensorAuthenticator(BaseAnomalyDetector):

    def __init__(
        self,
        input_dim: int,
        seq_len: int,
        batch_size: int = 32,
        learning_rate: float = 1e-3,
        svdd_weight: float = 0.1,  # Weight for the SVDD compactness loss
        dropout_rate: float = 0.1, # Regularization to prevent identity-mapping
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)

        self.seq_len = seq_len
        self.input_dim = input_dim
        self.svdd_weight = svdd_weight
        self.dropout_rate = dropout_rate

        # Ensure seq_len is compatible with the decoder's upsampling
        if seq_len % 4 != 0:
            raise ValueError("seq_len must be divisible by 4 for the UpSampling layers.")

        # ----------------------------------------------------------
        # SVDD CENTER (TFLite-safe trainable variable)
        # ----------------------------------------------------------
        self.center = self.add_weight(
            name="svdd_center",
            shape=(32,),
            initializer="zeros",
            trainable=False
        )

        # ----------------------------------------------------------
        # ENCODER
        # ----------------------------------------------------------
        self.enc_conv1 = tf.keras.layers.Conv1D(32, 5, strides=2, padding='same')
        self.enc_ln1 = tf.keras.layers.LayerNormalization()
        self.enc_act1 = tf.keras.layers.ReLU()
        # Removed tf.keras.layers.Dropout to prevent Untracked Resource Error

        self.enc_conv2 = tf.keras.layers.SeparableConv1D(64, 3, strides=2, padding='same')
        self.enc_ln2 = tf.keras.layers.LayerNormalization()
        self.enc_act2 = tf.keras.layers.ReLU()
        # Removed tf.keras.layers.Dropout to prevent Untracked Resource Error

        # Flatten preserves sequential relationships (unlike GlobalAveragePooling)
        self.enc_flatten = tf.keras.layers.Flatten()
        self.latent_dense = tf.keras.layers.Dense(32)

        # ----------------------------------------------------------
        # DECODER
        # ----------------------------------------------------------
        self.dec_dense = tf.keras.layers.Dense((seq_len // 4) * 64, activation='relu')
        self.dec_reshape = tf.keras.layers.Reshape((seq_len // 4, 64))

        self.dec_up1 = tf.keras.layers.UpSampling1D(2)
        self.dec_conv1 = tf.keras.layers.SeparableConv1D(64, 3, padding='same', activation='relu')

        self.dec_up2 = tf.keras.layers.UpSampling1D(2)
        self.dec_conv2 = tf.keras.layers.SeparableConv1D(32, 3, padding='same', activation='relu')

        self.dec_out = tf.keras.layers.Conv1D(input_dim, 3, padding='same')

        self.optimizer = tf.keras.optimizers.Adam(learning_rate)

        # Initialize graph and weights
        self._build()
        self.bake_weights()

    # ----------------------------------------------------------
    # REQUIRED BUILD STEP
    # ----------------------------------------------------------
    def _build(self):
        """Forces the creation of layer and optimizer variables."""
        x = tf.zeros((1, self.seq_len, self.input_dim))
        
        with tf.GradientTape() as tape:
            latent = self._encode(x, training=True)
            recon = self._decode(latent)
            loss = tf.reduce_mean(tf.square(x - recon))
            
        grads = tape.gradient(loss, self.trainable_weights)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))
        
        # Reset center back to strict zeros after dummy step
        self.center.assign(tf.zeros_like(self.center))

    # ----------------------------------------------------------
    # ENCODER
    # ----------------------------------------------------------
    def _encode(self, x, training=False):
        x = self.enc_conv1(x)
        x = self.enc_ln1(x)
        x = self.enc_act1(x)
        
        # Safe Dropout Implementation for TFLite / Custom Signatures
        if self.dropout_rate > 0.0:
            x = tf.cond(
                tf.cast(training, tf.bool),
                lambda: tf.nn.dropout(x, rate=self.dropout_rate),
                lambda: x
            )

        x = self.enc_conv2(x)
        x = self.enc_ln2(x)
        x = self.enc_act2(x)
        
        if self.dropout_rate > 0.0:
            x = tf.cond(
                tf.cast(training, tf.bool),
                lambda: tf.nn.dropout(x, rate=self.dropout_rate),
                lambda: x
            )

        x = self.enc_flatten(x)
        return self.latent_dense(x)

    # ----------------------------------------------------------
    # DECODER
    # ----------------------------------------------------------
    def _decode(self, z):
        x = self.dec_dense(z)
        x = self.dec_reshape(x)

        x = self.dec_up1(x)
        x = self.dec_conv1(x)

        x = self.dec_up2(x)
        x = self.dec_conv2(x)

        return self.dec_out(x)

    def call(self, inputs, training=False):
        latent = self._encode(inputs, training=training)
        recon = self._decode(latent)
        return recon, latent

    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    @property
    def persistent_weights(self) -> List[tf.Variable]:
        """Gathers all weights required for export, including center and optimizer."""
        return self.trainable_weights + self.non_trainable_weights + self.optimizer.variables

    # ----------------------------------------------------------
    # TRAIN STEP
    # ----------------------------------------------------------
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[BATCH_SIZE, SEQUENCE_LENGTH, SENSOR_INPUT_DIM], dtype=tf.float32)
    ])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        with tf.GradientTape() as tape:
            latent = self._encode(inputs, training=True)
            recon = self._decode(latent)
            
            # 1. Reconstruction Loss
            recon_loss = tf.reduce_mean(tf.square(inputs - recon))
            
            # 2. SVDD Compactness Loss
            svdd_loss = tf.reduce_mean(
                tf.reduce_sum(tf.square(latent - tf.stop_gradient(self.center)), axis=1)
            )
            
            # Total Loss
            total_loss = recon_loss + (self.svdd_weight * svdd_loss)

        grads = tape.gradient(total_loss, self.trainable_weights)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))

        # Update SVDD center via Exponential Moving Average (EMA)
        batch_center = tf.reduce_mean(latent, axis=0)
        self.center.assign(0.9 * self.center + 0.1 * batch_center)

        return {
            "loss_ae_total": total_loss,
            "loss_recon": recon_loss,
            "loss_svdd": svdd_loss
        }

    # ----------------------------------------------------------
    # INFERENCE
    # ----------------------------------------------------------
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None, SEQUENCE_LENGTH, SENSOR_INPUT_DIM], dtype=tf.float32)
    ])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        latent = self._encode(inputs, training=False)
        recon = self._decode(latent)

        recon_error = tf.reduce_mean(tf.square(inputs - recon), axis=[1, 2])
        latent_dist = tf.reduce_sum(tf.square(latent - self.center), axis=1)

        # Tune these weights based on sensor characteristics
        scores = 0.7 * recon_error + 0.3 * latent_dist

        return {
            "anomaly_score": scores,
            "reconstruction": recon
        }

    # ----------------------------------------------------------
    # BAKE INITIAL WEIGHTS
    # ----------------------------------------------------------
    def bake_weights(self) -> None:
        self.baked_weights = [
            tf.identity(v) for v in self.persistent_weights
        ]

    # ----------------------------------------------------------
    # INIT / RESET MODEL
    # ----------------------------------------------------------
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1], dtype=tf.float32)
    ])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        for var, init in zip(self.persistent_weights, self.baked_weights):
            var.assign(init)
        
        self.center.assign(tf.zeros_like(self.center))
        return {"status": tf.constant(1.0)}

    # ----------------------------------------------------------
    # SAVE WEIGHTS (TFLITE SAFE EXPORT)
    # ----------------------------------------------------------
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1], dtype=tf.float32)
    ])
    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        return {
            f"val_{i}": v
            for i, v in enumerate(self.persistent_weights)
        }

    # ----------------------------------------------------------
    # RESTORE WEIGHTS (TFLITE SAFE IMPORT)
    # ----------------------------------------------------------
    @tf.function
    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        for i, var in enumerate(self.persistent_weights):
            var.assign(kwargs[f"val_{i}"])
        return {"status": tf.constant(1.0)}