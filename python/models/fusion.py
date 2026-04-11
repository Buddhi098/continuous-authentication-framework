import tensorflow as tf
from typing import Dict, Any, List
from .base import BaseAnomalyDetector
from config import BATCH_SIZE, FUSION_INPUT_DIM

class MaskedFusionAuthenticator(BaseAnomalyDetector):

    def __init__(
        self,
        input_dim: int,
        mask_rate: float = 0.25,
        batch_size: int = 32,
        learning_rate: float = 1e-3,
        **kwargs
    ):
        super().__init__(input_dim=input_dim, batch_size=batch_size, **kwargs)

        self.input_dim = input_dim
        self.mask_rate = mask_rate

        # ==========================================================
        # SVDD CENTER (TFLite-safe trainable variable)
        # ==========================================================
        self.center = self.add_weight(
            name="svdd_center",
            shape=(32,),
            initializer="zeros",
            trainable=False
        )

        # ==========================================================
        # ENCODER (Dense for 2D inputs: Batch x Features)
        # ==========================================================
        self.enc_dense1 = tf.keras.layers.Dense(64)
        self.enc_ln1 = tf.keras.layers.LayerNormalization()
        self.enc_act1 = tf.keras.layers.ReLU()

        self.enc_dense2 = tf.keras.layers.Dense(32)
        self.enc_ln2 = tf.keras.layers.LayerNormalization()
        self.enc_act2 = tf.keras.layers.ReLU()

        self.latent_dense = tf.keras.layers.Dense(32)

        # ==========================================================
        # DECODER
        # ==========================================================
        self.dec_dense1 = tf.keras.layers.Dense(32, activation='relu')
        self.dec_dense2 = tf.keras.layers.Dense(64, activation='relu')
        self.dec_out = tf.keras.layers.Dense(input_dim)

        self.optimizer = tf.keras.optimizers.Adam(learning_rate)
        self.loss_fn = tf.keras.losses.MeanSquaredError()

        self._build()
        self.bake_weights()

    # ==========================================================
    # REQUIRED BUILD STEP
    # ==========================================================
    def _build(self):
        """Forces the creation of layer and optimizer variables."""
        # 1. Forward pass to build layer weights (Shape: Batch x Features)
        x = tf.zeros((1, self.input_dim))
        
        # 2. Dummy training step to force optimizer to build its slot variables
        with tf.GradientTape() as tape:
            latent = self._encode(x)
            recon = self._decode(latent)
            loss = tf.reduce_mean(tf.square(x - recon))
            
        grads = tape.gradient(loss, self.trainable_weights)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))
        
        # 3. Reset center back to strict zeros (dummy step might shift it)
        self.center.assign(tf.zeros_like(self.center))

    # ==========================================================
    # ENCODER
    # ==========================================================
    def _encode(self, x):
        x = self.enc_dense1(x)
        x = self.enc_ln1(x)
        x = self.enc_act1(x)

        x = self.enc_dense2(x)
        x = self.enc_ln2(x)
        x = self.enc_act2(x)

        return self.latent_dense(x)

    # ==========================================================
    # DECODER
    # ==========================================================
    def _decode(self, z):
        x = self.dec_dense1(z)
        x = self.dec_dense2(x)
        return self.dec_out(x)

    # ==========================================================
    # FORWARD PASS
    # ==========================================================
    def call(self, inputs, training=False):
        # We generally do not mask during the standard forward pass / evaluation
        latent = self._encode(inputs)
        recon = self._decode(latent)
        return recon, latent

    # ==========================================================
    # REQUIRED PROPERTIES
    # ==========================================================
    @property
    def signature_keys(self) -> List[str]:
        return ["train", "infer", "init_model", "save", "restore"]

    @property
    def persistent_weights(self) -> List[tf.Variable]:
        """Gathers all weights required for export, including center and optimizer."""
        return self.trainable_weights + self.non_trainable_weights + self.optimizer.variables

    # ==========================================================
    # TRAIN STEP (Masked Autoencoder Logic)
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[BATCH_SIZE, FUSION_INPUT_DIM], dtype=tf.float32)
    ])
    def train_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        # Create a boolean mask: True for unmasked, False for masked
        mask = tf.cast(
            tf.random.uniform(tf.shape(inputs)) > self.mask_rate,
            dtype=tf.float32
        )
        
        # Apply the mask to inputs (zeroing out 'mask_rate' fraction of features)
        masked_inputs = inputs * mask

        with tf.GradientTape() as tape:
            latent = self._encode(masked_inputs)
            recon = self._decode(latent)
            
            # Loss forces the model to reconstruct the full unmasked input
            recon_loss = tf.reduce_mean(tf.square(inputs - recon))

        grads = tape.gradient(recon_loss, self.trainable_weights)
        self.optimizer.apply_gradients(zip(grads, self.trainable_weights))

        # SVDD center update
        batch_center = tf.reduce_mean(latent, axis=0)
        self.center.assign(0.9 * self.center + 0.1 * batch_center)

        return {"loss_ae_total": recon_loss}

    # ==========================================================
    # INFERENCE
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[None, FUSION_INPUT_DIM], dtype=tf.float32)
    ])
    def infer_func(self, inputs: tf.Tensor) -> Dict[str, tf.Tensor]:
        # Inference takes unmasked complete inputs to score the anomaly
        latent = self._encode(inputs)
        recon = self._decode(latent)

        # Axes modified to 1 since shape is [Batch, Feature]
        recon_error = tf.reduce_mean(tf.square(inputs - recon), axis=1)
        latent_dist = tf.reduce_sum(tf.square(latent - self.center), axis=1)

        scores = 0.7 * recon_error + 0.3 * latent_dist

        return {
            "anomaly_score": scores,
            "reconstruction": recon
        }

    # ==========================================================
    # BAKE INITIAL WEIGHTS
    # ==========================================================
    def bake_weights(self) -> None:
        self.baked_weights = [
            tf.identity(v) for v in self.persistent_weights
        ]

    # ==========================================================
    # INIT / RESET MODEL
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1], dtype=tf.float32)
    ])
    def init_model(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        for var, init in zip(self.persistent_weights, self.baked_weights):
            var.assign(init)
        
        self.center.assign(tf.zeros_like(self.center))
        return {"status": tf.constant(1.0)}

    # ==========================================================
    # SAVE WEIGHTS (TFLITE SAFE EXPORT)
    # ==========================================================
    @tf.function(input_signature=[
        tf.TensorSpec(shape=[1], dtype=tf.float32)
    ])
    def save_weights_func(self, x: tf.Tensor) -> Dict[str, tf.Tensor]:
        return {
            f"val_{i}": v
            for i, v in enumerate(self.persistent_weights)
        }

    # ==========================================================
    # RESTORE WEIGHTS (TFLITE SAFE IMPORT)
    # ==========================================================
    @tf.function
    def restore_weights_func(self, **kwargs) -> Dict[str, tf.Tensor]:
        for i, var in enumerate(self.persistent_weights):
            var.assign(kwargs[f"val_{i}"])
        return {"status": tf.constant(1.0)}