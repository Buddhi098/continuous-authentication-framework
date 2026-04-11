import tensorflow as tf
from typing import Dict, List
from .base import BaseAnomalyDetector
from config import BATCH_SIZE, FUSION_INPUT_DIM

class MaskedFusionAuthenticator(BaseAnomalyDetector):

    def __init__(
        self,
        input_dim: int,
        mask_rate: float = 0.25,
        batch_size: int = 32,
        learning_rate: float = 1e-3,
        svdd_warmup_steps: int = 100,
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)

        self.input_dim = input_dim
        self.mask_rate = mask_rate
        self.svdd_warmup_steps = tf.Variable(0, trainable=False, dtype=tf.int32)
        self.svdd_warmup_limit = svdd_warmup_steps

        # SVDD CENTER
        self.center = self.add_weight(
            name="svdd_center",
            shape=(16,),
            initializer="zeros",
            trainable=False
        )

        # ==========================================================
        # ENCODER
        # ==========================================================
        self.enc_dense1 = tf.keras.layers.Dense(128)
        self.enc_ln1 = tf.keras.layers.LayerNormalization()
        self.enc_act1 = tf.keras.layers.LeakyReLU(negative_slope=0.2)

        self.enc_dense2 = tf.keras.layers.Dense(64)
        self.enc_ln2 = tf.keras.layers.LayerNormalization()
        self.enc_act2 = tf.keras.layers.LeakyReLU(negative_slope=0.2)
        
        self.enc_dense3 = tf.keras.layers.Dense(32)
        self.enc_ln3 = tf.keras.layers.LayerNormalization()
        self.enc_act3 = tf.keras.layers.LeakyReLU(negative_slope=0.2)

        self.latent_dense = tf.keras.layers.Dense(16)

        # ==========================================================
        # DECODER (Enhanced: Added LayerNorm for symmetry)
        # ==========================================================
        self.dec_dense1 = tf.keras.layers.Dense(32)
        self.dec_ln1 = tf.keras.layers.LayerNormalization()
        self.dec_act1 = tf.keras.layers.LeakyReLU(negative_slope=0.2)
        
        self.dec_dense2 = tf.keras.layers.Dense(64)
        self.dec_ln2 = tf.keras.layers.LayerNormalization()
        self.dec_act2 = tf.keras.layers.LeakyReLU(negative_slope=0.2)
        
        self.dec_dense3 = tf.keras.layers.Dense(128)
        self.dec_ln3 = tf.keras.layers.LayerNormalization()
        self.dec_act3 = tf.keras.layers.LeakyReLU(negative_slope=0.2)
        
        self.dec_out = tf.keras.layers.Dense(input_dim)

        self.optimizer = tf.keras.optimizers.Adam(learning_rate)

        self._build()
        self.bake_weights()

    def _build(self):
        x = tf.zeros((1, self.input_dim))
        with tf.GradientTape() as tape:
            z = self._encode(x, training=True)
            recon = self._decode(z, training=True)
            loss = tf.reduce_mean(tf.square(x - recon))

        grads = tape.gradient(loss, self.trainable_weights)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))

    def _encode(self, x, training=False):
        x = self.enc_dense1(x)
        x = self.enc_ln1(x, training=training)
        x = self.enc_act1(x)
        
        if training:
            x = tf.nn.dropout(x, rate=0.2)

        x = self.enc_dense2(x)
        x = self.enc_ln2(x, training=training)
        x = self.enc_act2(x)
        
        x = self.enc_dense3(x)
        x = self.enc_ln3(x, training=training)
        x = self.enc_act3(x)

        z = self.latent_dense(x)
        # ENHANCEMENT: L2 normalize to bound the SVDD hypersphere
        return tf.math.l2_normalize(z, axis=1)

    def _decode(self, z, training=False):
        x = self.dec_dense1(z)
        x = self.dec_ln1(x, training=training)
        x = self.dec_act1(x)
        
        x = self.dec_dense2(x)
        x = self.dec_ln2(x, training=training)
        x = self.dec_act2(x)
        
        x = self.dec_dense3(x)
        x = self.dec_ln3(x, training=training)
        x = self.dec_act3(x)
        
        return self.dec_out(x)

    def call(self, inputs, training=False):
        z = self._encode(inputs, training=training)
        recon = self._decode(z, training=training)
        return recon, z

    @tf.function(input_signature=[
        tf.TensorSpec(shape=[BATCH_SIZE, FUSION_INPUT_DIM], dtype=tf.float32)
    ])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:

        mask = tf.cast(
            tf.random.uniform(tf.shape(inputs)) > self.mask_rate,
            tf.float32
        )
        noise = tf.random.normal(tf.shape(inputs), stddev=0.05)
        masked_inputs = inputs * mask + noise * (1 - mask)

        with tf.GradientTape() as tape:
            latent = self._encode(masked_inputs, training=True)
            recon = self._decode(latent, training=True)

            mse_loss = tf.reduce_mean(tf.square(inputs - recon))
            mae_loss = tf.reduce_mean(tf.abs(inputs - recon))
            recon_loss = mse_loss + mae_loss

            svdd_loss = tf.reduce_mean(tf.reduce_sum(tf.square(latent - self.center), axis=1))
            
            # ENHANCEMENT: Delayed SVDD penalty based on warmup phase
            svdd_weight = tf.cond(
                self.svdd_warmup_steps < self.svdd_warmup_limit,
                lambda: tf.constant(0.0),
                lambda: tf.constant(0.1)
            )
            
            total_loss = recon_loss + svdd_weight * svdd_loss

        grads = tape.gradient(total_loss, self.trainable_weights)
        grads, _ = tf.clip_by_global_norm(grads, 5.0)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))

        batch_center = tf.reduce_mean(latent, axis=0)

        def update_center():
            # Slowly move center towards batch center
            self.center.assign(0.9 * self.center + 0.1 * batch_center)
            # L2 normalize the center to match latent constraint
            self.center.assign(tf.math.l2_normalize(self.center)) 
            self.svdd_warmup_steps.assign_add(1)
            return self.center

        tf.cond(
            self.svdd_warmup_steps < self.svdd_warmup_limit,
            update_center,
            lambda: self.center
        )

        return {"loss_ae_total": total_loss, "svdd_loss": svdd_loss}

    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None, FUSION_INPUT_DIM], dtype=tf.float32)
    ])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:

        latent = self._encode(inputs, training=False)
        recon = self._decode(latent, training=False)

        recon_error_mse = tf.reduce_mean(tf.square(inputs - recon), axis=1)
        recon_error_mae = tf.reduce_mean(tf.abs(inputs - recon), axis=1)
        
        # ENHANCEMENT: log1p compresses extreme recon scales to align better with bounded latent_dist
        recon_error = tf.math.log1p(recon_error_mse + recon_error_mae)
        
        latent_dist = tf.reduce_sum(tf.square(latent - self.center), axis=1)

        scores = 0.7 * recon_error + 0.3 * latent_dist

        return {
            "anomaly_score": scores,
            "reconstruction": recon
        }

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

    @tf.function(input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32)])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        for var, init in zip(self.persistent_weights, self.baked_weights):
            var.assign(init)
        self.center.assign(tf.zeros_like(self.center))
        self.svdd_warmup_steps.assign(0) # Reset warmup on init
        return {"status": tf.constant(1.0)}

    @tf.function(input_signature=[tf.TensorSpec(shape=[1], dtype=tf.float32)])
    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        return {f"val_{i}": v for i, v in enumerate(self.persistent_weights)}

    @tf.function
    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        for i, var in enumerate(self.persistent_weights):
            var.assign(kwargs[f"val_{i}"])
        return {"status": tf.constant(1.0)}