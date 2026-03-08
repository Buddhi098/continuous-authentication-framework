"""
Deep Autoencoder with deterministic inference for One-Class Anomaly Detection.

Improvements over baseline for continuous authentication via IMU sensor data:

1. ARCHITECTURE
   - Wider network (256→128→64 encoder, symmetric decoder) to better capture the
     rich statistical feature space of accelerometer + gyroscope signals.
   - Extra encoder/decoder layer pair for deeper feature abstraction.
   - tanh-activated bottleneck: bounds the latent space to [-1, 1], concentrating
     normal user representations near the origin and making imposter deviations
     more geometrically distinct.
   - Built-in input LayerNorm: absorbs per-session sensor magnitude drift before
     any learned transformation, reducing false positives on amplitude changes.

2. TRAINING STRATEGY — SVDD-Inspired Compactness Loss
   - Adds a compactness term λ·‖z‖² to the reconstruction loss, directly minimising
     the volume of the hypersphere enclosing legitimate-user latent codes.
   - Result: normal samples cluster tightly near the origin; imposters (unseen during
     training) land far from it, producing larger latent-deviation scores.
   - λ = 0.1 balances reconstruction fidelity with latent compactness.

3. ANOMALY SCORING — Dual-Signal Score
   - Reconstruction Error (MSE): captures feature-level deviations (e.g. wrong
     motion magnitude or frequency content).
   - Latent Deviation (‖z‖²): captures holistic behavioural divergence in the
     compressed representation — an imposter may accidentally reconstruct some
     features well yet still occupy a different latent region.
   - Max Absolute Error: retained for sensitivity to single extreme-deviation
     features (e.g. sudden gyroscope spike an imposter cannot mimic).
   - Weights 0.5 / 0.3 / 0.2 chosen to prioritise reconstruction while leveraging
     the complementary signals.

4. OPTIMISER
   - CosineDecayRestarts LR schedule: periodic warm restarts escape sharp local
     minima that trap one-class models trained on limited normal data.
   - clipnorm=1.0: stabilises early training when the compactness gradient
     occasionally conflicts with the reconstruction gradient.
"""

import tensorflow as tf
from tensorflow.keras import regularizers
from typing import Dict, List, Any

from .base import BaseAnomalyDetector
from config import (
    DEFAULT_INPUT_DIM, DEFAULT_BATCH_SIZE,
    DEFAULT_DROPOUT_RATE, DEFAULT_L2_REG
)

# Weight of compactness regularisation term in the training loss.
# Higher values shrink the normal cluster more aggressively at the cost of
# slightly higher reconstruction error on legitimate users.
_COMPACT_LOSS_WEIGHT = 0.1

# Anomaly score blending weights (must sum to 1.0).
_W_MSE        = 0.5   # per-feature reconstruction fidelity
_W_LATENT     = 0.3   # holistic behavioural deviation in latent space
_W_MAX_ERROR  = 0.2   # sensitivity to single extreme-deviation features


class DeepAnomalyAutoencoder(BaseAnomalyDetector):
    """
    Deep Autoencoder optimized for one-class anomaly detection.
    Deterministic reconstruction with dual-signal scoring (reconstruction + latent
    deviation) for improved legitimate-user / imposter separation.
    """

    def __init__(
        self,
        input_dim: int = DEFAULT_INPUT_DIM,
        batch_size: int = DEFAULT_BATCH_SIZE,
        dropout_rate: float = DEFAULT_DROPOUT_RATE,
        l2_reg: float = DEFAULT_L2_REG,
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)
        self.dropout_rate = dropout_rate

        reg = regularizers.l2(l2_reg)

        # -------------- Input normalisation --------------
        # Absorbs per-session amplitude drift from the raw sensor pipeline
        # before any learned transformation, improving cross-session stability.
        self.input_norm = tf.keras.layers.LayerNormalization(name="input_norm")

        # ---------------- Encoder ----------------
        # Wider first layer to span the full accelerometer + gyroscope feature space.
        self.enc_dense1 = tf.keras.layers.Dense(256, kernel_regularizer=reg)
        self.enc_norm1  = tf.keras.layers.LayerNormalization()
        self.enc_act1   = tf.keras.layers.LeakyReLU(0.2)

        self.enc_dense2 = tf.keras.layers.Dense(128, kernel_regularizer=reg)
        self.enc_norm2  = tf.keras.layers.LayerNormalization()
        self.enc_act2   = tf.keras.layers.LeakyReLU(0.2)

        self.enc_dense3 = tf.keras.layers.Dense(64, kernel_regularizer=reg)
        self.enc_norm3  = tf.keras.layers.LayerNormalization()
        self.enc_act3   = tf.keras.layers.LeakyReLU(0.2)

        # tanh bottleneck: bounds latent codes to [-1,1], concentrating
        # the normal user manifold near the origin for tighter SVDD-style
        # compactness regularisation.
        self.bottleneck = tf.keras.layers.Dense(
            32, activation="tanh", name="bottleneck", kernel_regularizer=reg
        )

        # ---------------- Decoder ----------------
        self.dec_dense1 = tf.keras.layers.Dense(64, kernel_regularizer=reg)
        self.dec_norm1  = tf.keras.layers.LayerNormalization()
        self.dec_act1   = tf.keras.layers.LeakyReLU(0.2)

        self.dec_dense2 = tf.keras.layers.Dense(128, kernel_regularizer=reg)
        self.dec_norm2  = tf.keras.layers.LayerNormalization()
        self.dec_act2   = tf.keras.layers.LeakyReLU(0.2)

        self.dec_dense3 = tf.keras.layers.Dense(256, kernel_regularizer=reg)
        self.dec_norm3  = tf.keras.layers.LayerNormalization()
        self.dec_act3   = tf.keras.layers.LeakyReLU(0.2)

        self.dec_output = tf.keras.layers.Dense(
            input_dim, activation="linear", name="reconstruction"
        )

        # ---------------- Optimiser ----------------
        # CosineDecayRestarts allows periodic warm restarts that help the model
        # escape sharp local minima common in one-class training on small normal sets.
        lr_schedule = tf.keras.optimizers.schedules.CosineDecayRestarts(
            initial_learning_rate=1e-3,
            first_decay_steps=500,
            t_mul=2.0,   # double the restart period each cycle
            m_mul=0.9,   # slightly reduce peak LR each restart
            alpha=1e-5   # minimum LR floor
        )
        self.optimizer = tf.keras.optimizers.Adam(
            learning_rate=lr_schedule,
            clipnorm=1.0  # gradient clipping prevents compactness term from destabilising training
        )

    # ------------------------------------------------------------------
    # Public interface (unchanged)
    # ------------------------------------------------------------------

    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    # ------------------------------------------------------------------
    # Internal encode / decode helpers
    # ------------------------------------------------------------------

    def _encode(self, inputs: tf.Tensor, training: bool = False) -> tf.Tensor:
        """Return the bottleneck representation for a batch of sensor features."""
        x = self.input_norm(inputs)

        x = self.enc_dense1(x)
        x = self.enc_norm1(x)
        x = self.enc_act1(x)
        if training:
            x = tf.nn.dropout(x, rate=self.dropout_rate)

        x = self.enc_dense2(x)
        x = self.enc_norm2(x)
        x = self.enc_act2(x)
        if training:
            x = tf.nn.dropout(x, rate=self.dropout_rate)

        x = self.enc_dense3(x)
        x = self.enc_norm3(x)
        x = self.enc_act3(x)
        if training:
            x = tf.nn.dropout(x, rate=self.dropout_rate)

        return self.bottleneck(x)

    def _decode(self, encoded: tf.Tensor, training: bool = False) -> tf.Tensor:
        """Reconstruct sensor features from bottleneck representation."""
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

        x = self.dec_dense3(x)
        x = self.dec_norm3(x)
        x = self.dec_act3(x)
        if training:
            x = tf.nn.dropout(x, rate=self.dropout_rate)

        return self.dec_output(x)

    # ------------------------------------------------------------------
    # Keras model interface (unchanged signature)
    # ------------------------------------------------------------------

    def call(self, inputs: tf.Tensor, training: bool = False) -> tf.Tensor:
        encoded = self._encode(inputs, training=training)
        return self._decode(encoded, training=training)

    # ------------------------------------------------------------------
    # Anomaly scoring
    # ------------------------------------------------------------------

    def compute_anomaly_score(
        self,
        inputs: tf.Tensor,
        reconstruction: tf.Tensor,
        encoded: tf.Tensor = None
    ) -> tf.Tensor:
        """
        Dual-signal anomaly score combining:
          - MSE reconstruction error  (feature-level deviation)
          - Latent L2 deviation        (holistic behavioural divergence)
          - Max absolute feature error (sensitivity to extreme single-feature spikes)

        If `encoded` is not supplied the latent term is omitted gracefully,
        preserving backward compatibility with callers of the original signature.
        """
        mse       = tf.reduce_mean(tf.square(inputs - reconstruction), axis=1)
        max_error = tf.reduce_max(tf.abs(inputs - reconstruction), axis=1)

        if encoded is not None:
            # ‖z‖² measures how far the latent code is from the origin.
            # Normal samples are pulled toward the origin by the compactness loss,
            # so imposters naturally produce larger values here.
            latent_dev = tf.reduce_mean(tf.square(encoded), axis=1)
            return _W_MSE * mse + _W_LATENT * latent_dev + _W_MAX_ERROR * max_error

        # Fallback: original two-signal score (keeps interface compatible)
        return 0.7 * mse + 0.3 * max_error

    # ------------------------------------------------------------------
    # Core functions called by signatures
    # ------------------------------------------------------------------

    def bake_weights(self) -> None:
        self.baked_weights = [
            tf.constant(w.numpy(), dtype=w.dtype)
            for w in self.trainable_variables
        ]

    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        encoded        = self._encode(inputs, training=False)
        reconstruction = self._decode(encoded, training=False)

        reconstruction_error = tf.reduce_mean(
            tf.square(inputs - reconstruction), axis=1
        )
        anomaly_score = self.compute_anomaly_score(inputs, reconstruction, encoded)

        return {
            "reconstruction":       reconstruction,
            "reconstruction_error": reconstruction_error,
            "anomaly_score":        anomaly_score,
        }

    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        with tf.GradientTape() as tape:
            encoded        = self._encode(inputs, training=True)
            reconstruction = self._decode(encoded, training=True)

            # Primary reconstruction loss
            recon_loss = tf.reduce_mean(tf.square(inputs - reconstruction))

            # Compactness regularisation: pulls normal latent codes toward the
            # origin so that imposter codes are geometrically distant.
            # Equivalent to minimising the radius of the SVDD hypersphere
            # centred at the origin in the tanh-bounded latent space.
            compactness_loss = tf.reduce_mean(tf.reduce_sum(tf.square(encoded), axis=1))

            loss = recon_loss + _COMPACT_LOSS_WEIGHT * compactness_loss

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

    # ------------------------------------------------------------------
    # Saved-model signatures (unchanged interface)
    # ------------------------------------------------------------------

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

        signatures["infer"]      = infer_fn
        signatures["train"]      = train_fn
        signatures["init_model"] = init_fn
        return signatures