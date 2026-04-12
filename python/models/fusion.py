import tensorflow as tf
from typing import Dict, List
from .base import BaseAnomalyDetector
from config import BATCH_SIZE, FUSION_INPUT_DIM


class ResidualFeatureSVDDAuthenticator(BaseAnomalyDetector):

    def __init__(
        self,
        input_dim: int,
        batch_size: int = 32,
        learning_rate: float = 1e-3,
        svdd_warmup_steps: int = 100,
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)

        self.input_dim = input_dim
        self.svdd_warmup_steps = tf.Variable(0, trainable=False, dtype=tf.int32)
        self.svdd_warmup_limit = svdd_warmup_steps

        # ==========================================================
        # SVDD CENTER
        # ==========================================================
        self.center = self.add_weight(
            name="svdd_center",
            shape=(32,),
            initializer="zeros",
            trainable=False
        )

        # ==========================================================
        # ENCODER (Residual + Attention)
        # ==========================================================
        self.enc_dense1 = tf.keras.layers.Dense(128)
        self.enc_ln1 = tf.keras.layers.LayerNormalization()
        self.enc_act1 = tf.keras.layers.LeakyReLU(0.2)

        self.enc_dense2 = tf.keras.layers.Dense(128)
        self.enc_ln2 = tf.keras.layers.LayerNormalization()

        self.enc_dense3 = tf.keras.layers.Dense(128)

        # Feature attention (gating)
        self.attn_dense = tf.keras.layers.Dense(128, activation="sigmoid")

        self.enc_act2 = tf.keras.layers.LeakyReLU(0.2)

        self.enc_dense4 = tf.keras.layers.Dense(64)
        self.enc_ln3 = tf.keras.layers.LayerNormalization()
        self.enc_act3 = tf.keras.layers.LeakyReLU(0.2)

        self.latent_dense = tf.keras.layers.Dense(32)

        # ==========================================================
        # DECODER
        # ==========================================================
        self.dec_dense1 = tf.keras.layers.Dense(64)
        self.dec_act1 = tf.keras.layers.LeakyReLU(0.2)

        self.dec_dense2 = tf.keras.layers.Dense(128)
        self.dec_act2 = tf.keras.layers.LeakyReLU(0.2)

        self.dec_dense3 = tf.keras.layers.Dense(128)
        self.dec_act3 = tf.keras.layers.LeakyReLU(0.2)

        self.dec_out = tf.keras.layers.Dense(input_dim)

        self.optimizer = tf.keras.optimizers.Adam(learning_rate)

        self._build()
        self.bake_weights()

    # ==========================================================
    # BUILD
    # ==========================================================
    def _build(self):
        x = tf.zeros((1, self.input_dim))

        with tf.GradientTape() as tape:
            z = self._encode(x, training=True)
            recon = self._decode(z, training=True)
            loss = tf.reduce_mean(tf.square(x - recon))

        grads = tape.gradient(loss, self.trainable_weights)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))

    # ==========================================================
    # ENCODER (Residual + Attention)
    # ==========================================================
    def _encode(self, x, training=False):

        # Layer 1
        x1 = self.enc_dense1(x)
        x1 = self.enc_ln1(x1, training=training)
        x1 = self.enc_act1(x1)

        # Residual block
        res = x1
        x2 = self.enc_dense2(x1)
        x2 = self.enc_ln2(x2, training=training)

        x2 = self.enc_dense3(x2)

        # Attention gating
        gate = self.attn_dense(x2)
        x2 = x2 * gate

        # Residual connection
        x2 = x2 + res
        x2 = self.enc_act2(x2)

        # Final compression
        x3 = self.enc_dense4(x2)
        x3 = self.enc_ln3(x3, training=training)
        x3 = self.enc_act3(x3)

        z = self.latent_dense(x3)

        # Normalize latent space (VERY IMPORTANT for SVDD)
        z = tf.math.l2_normalize(z, axis=1)

        return z

    # ==========================================================
    # DECODER
    # ==========================================================
    def _decode(self, z, training=False):
        x = self.dec_dense1(z)
        x = self.dec_act1(x)

        x = self.dec_dense2(x)
        x = self.dec_act2(x)

        x = self.dec_dense3(x)
        x = self.dec_act3(x)

        return self.dec_out(x)

    def call(self, inputs, training=False):
        z = self._encode(inputs, training=training)
        recon = self._decode(z, training=training)
        return recon, z

    # ==========================================================
    # TRAIN STEP (NO MASKING)
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[BATCH_SIZE, FUSION_INPUT_DIM], dtype=tf.float32)
    ])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:

        with tf.GradientTape() as tape:
            latent = self._encode(inputs, training=True)
            recon = self._decode(latent, training=True)

            # Stronger reconstruction loss
            mse_loss = tf.reduce_mean(tf.square(inputs - recon))
            mae_loss = tf.reduce_mean(tf.abs(inputs - recon))
            recon_loss = mse_loss + 0.5 * mae_loss

            # SVDD loss
            svdd_loss = tf.reduce_mean(
                tf.reduce_sum(tf.square(latent - self.center), axis=1)
            )

            total_loss = recon_loss + 0.2 * svdd_loss

        grads = tape.gradient(total_loss, self.trainable_weights)
        grads, _ = tf.clip_by_global_norm(grads, 5.0)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))

        # Update center
        batch_center = tf.reduce_mean(latent, axis=0)

        def update_center():
            self.center.assign(0.9 * self.center + 0.1 * batch_center)
            self.svdd_warmup_steps.assign_add(1)
            return self.center

        tf.cond(
            self.svdd_warmup_steps < self.svdd_warmup_limit,
            update_center,
            lambda: self.center
        )

        return {"loss_ae_total": total_loss}

    # ==========================================================
    # INFERENCE (UNCHANGED RETURN TYPES)
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None, FUSION_INPUT_DIM], dtype=tf.float32)
    ])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:

        latent = self._encode(inputs, training=False)
        recon = self._decode(latent, training=False)

        recon_error_mse = tf.reduce_mean(tf.square(inputs - recon), axis=1)
        recon_error_mae = tf.reduce_mean(tf.abs(inputs - recon), axis=1)
        recon_error = recon_error_mse + recon_error_mae

        latent_dist = tf.reduce_sum(tf.square(latent - self.center), axis=1)

        scores = 0.5 * recon_error + 0.5 * latent_dist

        return {
            "anomaly_score": scores,
            "reconstruction": recon
        }

    # ==========================================================
    # PROPERTIES
    # ==========================================================
    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    @property
    def persistent_weights(self) -> List[tf.Variable]:
        opt_vars = getattr(self.optimizer, 'variables', [])
        if callable(opt_vars):
            opt_vars = opt_vars()
        return self.trainable_weights + self.non_trainable_weights + list(opt_vars)

    def bake_weights(self) -> None:
        self.baked_weights = [tf.identity(v) for v in self.persistent_weights]

    # ==========================================================
    # INIT / SAVE / RESTORE
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1], dtype=tf.float32)
    ])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        for var, init in zip(self.persistent_weights, self.baked_weights):
            var.assign(init)
        self.center.assign(tf.zeros_like(self.center))
        return {"status": tf.constant(1.0)}

    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1], dtype=tf.float32)
    ])
    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        return {f"val_{i}": v for i, v in enumerate(self.persistent_weights)}

    @tf.function
    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        for i, var in enumerate(self.persistent_weights):
            var.assign(kwargs[f"val_{i}"])
        return {"status": tf.constant(1.0)}