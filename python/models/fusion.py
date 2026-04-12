import tensorflow as tf
from typing import Dict, List
from .base import BaseAnomalyDetector
from config import BATCH_SIZE, FUSION_INPUT_DIM

class SVDDAuthCore(BaseAnomalyDetector):
    """
    SVDD-AuthCore (Enhanced - No Dropout)
    -------------------------
    Support Vector Deep Distance Authentication Core

    - Static feature vector authentication
    - Pure SVDD (no decoder, no masking)
    - Lightweight MLP encoder with L2 Reg (Hypersphere collapse prevention)
    - Fully mobile/TFLite compatible
    """

    def __init__(
        self,
        input_dim: int,
        latent_dim: int = 16,
        svdd_warmup_steps: int = 100,
        learning_rate: float = 1e-3,
        weight_decay: float = 1e-4,  # Crucial for preventing mode collapse
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=BATCH_SIZE, **kwargs)

        self.input_dim = input_dim
        self.latent_dim = latent_dim
        self.weight_decay = weight_decay

        # ======================================================
        # SVDD CENTER
        # ======================================================
        # Initialized to random normal rather than zeros to avoid immediate zero-collapse
        self.center = self.add_weight(
            name="svdd_center",
            shape=(latent_dim,),
            initializer=tf.keras.initializers.RandomNormal(mean=0.0, stddev=0.1),
            trainable=False
        )

        self.svdd_warmup_steps = tf.Variable(0, trainable=False, dtype=tf.int32)
        self.svdd_warmup_limit = svdd_warmup_steps

        # ======================================================
        # ENCODER (MLP)
        # ======================================================
        reg = tf.keras.regularizers.l2(self.weight_decay)

        self.enc_dense1 = tf.keras.layers.Dense(128, kernel_regularizer=reg)
        self.enc_ln1 = tf.keras.layers.LayerNormalization()
        self.enc_act1 = tf.keras.layers.LeakyReLU(0.2)

        self.enc_dense2 = tf.keras.layers.Dense(64, kernel_regularizer=reg)
        self.enc_ln2 = tf.keras.layers.LayerNormalization()
        self.enc_act2 = tf.keras.layers.LeakyReLU(0.2)

        self.enc_dense3 = tf.keras.layers.Dense(32, kernel_regularizer=reg)
        self.enc_ln3 = tf.keras.layers.LayerNormalization()
        self.enc_act3 = tf.keras.layers.LeakyReLU(0.2)

        # CRITICAL: use_bias=False prevents the trivial solution where 
        # the network just learns a bias equal to the SVDD center.
        self.latent_dense = tf.keras.layers.Dense(
            latent_dim, 
            use_bias=False, 
            kernel_regularizer=reg
        )

        self.optimizer = tf.keras.optimizers.Adam(learning_rate)

        self._build()
        self.bake_weights()

    # ======================================================
    # BUILD
    # ======================================================
    def _build(self):
        x = tf.zeros((1, self.input_dim))
        _ = self._encode(x, training=True)

    # ======================================================
    # ENCODER
    # ======================================================
    def _encode(self, x, training=False):
        x = self.enc_dense1(x)
        x = self.enc_ln1(x, training=training)
        x = self.enc_act1(x)

        x = self.enc_dense2(x)
        x = self.enc_ln2(x, training=training)
        x = self.enc_act2(x)

        x = self.enc_dense3(x)
        x = self.enc_ln3(x, training=training)
        x = self.enc_act3(x)

        return self.latent_dense(x)

    # ======================================================
    # CALL
    # ======================================================
    def call(self, inputs, training=False):
        return self._encode(inputs, training=training)

    # ======================================================
    # TRAIN STEP (PURE SVDD LOSS + REGULARIZATION)
    # ======================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[BATCH_SIZE, FUSION_INPUT_DIM], dtype=tf.float32)
    ])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:

        with tf.GradientTape() as tape:
            z = self._encode(inputs, training=True)

            # 1. Primary SVDD Distance Loss
            svdd_loss = tf.reduce_mean(
                tf.reduce_sum(tf.square(z - self.center), axis=1)
            )

            # 2. Regularization Loss (Crucial to prevent mode collapse)
            reg_loss = tf.math.add_n(self.losses) if self.losses else 0.0
            
            total_loss = svdd_loss + reg_loss

        grads = tape.gradient(total_loss, self.trainable_weights)
        grads, _ = tf.clip_by_global_norm(grads, 5.0)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))

        # ==================================================
        # CENTER UPDATE
        # ==================================================
        batch_center = tf.reduce_mean(z, axis=0)

        def update_center():
            self.center.assign(0.9 * self.center + 0.1 * batch_center)
            self.svdd_warmup_steps.assign_add(1)
            return self.center

        tf.cond(
            self.svdd_warmup_steps < self.svdd_warmup_limit,
            update_center,
            lambda: self.center
        )

        return {
            "loss_ae_total": total_loss, 
            "loss_svdd": svdd_loss,
            "loss_reg": reg_loss
        }

    # ======================================================
    # INFERENCE
    # ======================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None, FUSION_INPUT_DIM], dtype=tf.float32)
    ])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:

        z = self._encode(inputs, training=False)

        scores = tf.reduce_sum(tf.square(z - self.center), axis=1)

        return {
            "anomaly_score": scores,
            "reconstruction": z
        }

    # ======================================================
    # PROPERTIES
    # ======================================================
    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    @property
    def persistent_weights(self) -> List[tf.Variable]:
        opt_vars = getattr(self.optimizer, "variables", [])
        if callable(opt_vars):
            opt_vars = opt_vars()
        return self.trainable_weights + self.non_trainable_weights + list(opt_vars)

    def bake_weights(self) -> None:
        self.baked_weights = [tf.identity(v) for v in self.persistent_weights]

    # ======================================================
    # INIT / RESTORE
    # ======================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1], dtype=tf.float32)
    ])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:

        for var, init in zip(self.persistent_weights, self.baked_weights):
            var.assign(init)

        # Re-initialize center to random normal instead of zeros
        self.center.assign(tf.random.normal(shape=self.center.shape, mean=0.0, stddev=0.1))

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